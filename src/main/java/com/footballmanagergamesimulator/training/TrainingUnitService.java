package com.footballmanagergamesimulator.training;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.training.TrainingExtraRepositories.PlayerUnitAssignmentRepository;
import com.footballmanagergamesimulator.training.TrainingExtraRepositories.UnitCoachAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * Training units: three units (GOALKEEPING / DEFENCE / ATTACK), players
 * auto-assigned by position (user-overridable), coaches assigned per unit,
 * and a deterministic workload/quality model with full explainability.
 */
@Service
public class TrainingUnitService {

    public static final String GK = "GOALKEEPING";
    public static final String DEF = "DEFENCE";
    public static final String ATT = "ATTACK";
    public static final List<String> UNITS = List.of(GK, DEF, ATT);

    /** Base players a single coach can cover before quality erodes. */
    private static final int BASE_CAPACITY = 6;
    private static final int MAX_CAPACITY_BONUS = 6; // best coach covers 12

    private static final long PLAYER_TYPE_ID = 1L;

    private final PlayerUnitAssignmentRepository assignmentRepository;
    private final UnitCoachAssignmentRepository coachAssignmentRepository;
    private final HumanRepository humanRepository;

    public TrainingUnitService(PlayerUnitAssignmentRepository assignmentRepository,
                               UnitCoachAssignmentRepository coachAssignmentRepository,
                               HumanRepository humanRepository) {
        this.assignmentRepository = assignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.humanRepository = humanRepository;
    }

    /** Default unit for a canonical/base position. */
    public static String defaultUnit(String position) {
        if (position == null) return DEF;
        String p = position.trim().toUpperCase(Locale.ROOT);
        if (p.equals("GK")) return GK;
        if (p.startsWith("D") || p.equals("DM")) return DEF; // DR, DC, DL, DM
        return ATT; // MC, ML, MR, AMC, AML, AMR, ST and anything attacking
    }

    /** Ensure every current player has a unit assignment; return all assignments. */
    public List<PlayerUnitAssignment> syncAssignments(long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, PLAYER_TYPE_ID);
        List<PlayerUnitAssignment> existing = assignmentRepository.findAllByTeamId(teamId);
        Map<Long, PlayerUnitAssignment> byPlayer = new HashMap<>();
        for (PlayerUnitAssignment a : existing) byPlayer.put(a.getPlayerId(), a);

        List<PlayerUnitAssignment> toSave = new ArrayList<>();
        Set<Long> liveIds = new HashSet<>();
        for (Human h : players) {
            liveIds.add(h.getId());
            if (!byPlayer.containsKey(h.getId())) {
                PlayerUnitAssignment a = new PlayerUnitAssignment();
                a.setTeamId(teamId);
                a.setPlayerId(h.getId());
                a.setUnit(defaultUnit(h.getPosition()));
                a.setAutoAssigned(true);
                toSave.add(a);
            }
        }
        if (!toSave.isEmpty()) assignmentRepository.saveAll(toSave);

        // Drop assignments for players no longer on the team.
        List<PlayerUnitAssignment> stale = existing.stream()
                .filter(a -> !liveIds.contains(a.getPlayerId())).toList();
        if (!stale.isEmpty()) assignmentRepository.deleteAll(stale);

        return assignmentRepository.findAllByTeamId(teamId);
    }

    public PlayerUnitAssignment assignPlayer(long teamId, long playerId, String unit) {
        String u = normalizeUnit(unit);
        PlayerUnitAssignment a = assignmentRepository.findByTeamIdAndPlayerId(teamId, playerId);
        if (a == null) {
            a = new PlayerUnitAssignment();
            a.setTeamId(teamId);
            a.setPlayerId(playerId);
        }
        a.setUnit(u);
        a.setAutoAssigned(false); // user pinned
        return assignmentRepository.save(a);
    }

    public UnitCoachAssignment assignCoach(long teamId, long coachId, String unit) {
        String u = normalizeUnit(unit);
        for (UnitCoachAssignment ex : coachAssignmentRepository.findAllByTeamIdAndUnit(teamId, u)) {
            if (ex.getCoachId() == coachId) return ex; // already assigned
        }
        UnitCoachAssignment ca = new UnitCoachAssignment();
        ca.setTeamId(teamId);
        ca.setCoachId(coachId);
        ca.setUnit(u);
        return coachAssignmentRepository.save(ca);
    }

    public void removeCoach(long teamId, long assignmentId) {
        UnitCoachAssignment ca = coachAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        if (ca.getTeamId() != teamId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your team");
        }
        coachAssignmentRepository.delete(ca);
    }

    /**
     * Full units overview with workload/quality explainability.
     */
    public Map<String, Object> getUnitsOverview(long teamId) {
        syncAssignments(teamId);
        List<PlayerUnitAssignment> assignments = assignmentRepository.findAllByTeamId(teamId);
        List<UnitCoachAssignment> coachAssignments = coachAssignmentRepository.findAllByTeamId(teamId);

        Map<Long, Human> humans = new HashMap<>();
        for (Human h : humanRepository.findAllByTeamId(teamId)) humans.put(h.getId(), h);

        Map<String, List<Map<String, Object>>> unitPlayers = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> unitCoaches = new LinkedHashMap<>();
        for (String u : UNITS) {
            unitPlayers.put(u, new ArrayList<>());
            unitCoaches.put(u, new ArrayList<>());
        }
        for (PlayerUnitAssignment a : assignments) {
            Human h = humans.get(a.getPlayerId());
            if (h == null) continue;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("playerId", h.getId());
            pm.put("name", h.getName());
            pm.put("position", h.getPosition());
            pm.put("unit", a.getUnit());
            pm.put("autoAssigned", a.isAutoAssigned());
            unitPlayers.computeIfAbsent(a.getUnit(), k -> new ArrayList<>()).add(pm);
        }
        for (UnitCoachAssignment ca : coachAssignments) {
            Human c = humans.get(ca.getCoachId());
            if (c == null) continue;
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("assignmentId", ca.getId());
            cm.put("coachId", c.getId());
            cm.put("name", c.getName());
            cm.put("specialization", specializationOf(c));
            cm.put("capacity", coachCapacity(c, ca.getUnit()));
            unitCoaches.computeIfAbsent(ca.getUnit(), k -> new ArrayList<>()).add(cm);
        }

        List<Map<String, Object>> units = new ArrayList<>();
        for (String u : UNITS) {
            int playerCount = unitPlayers.get(u).size();
            int totalCapacity = unitCoaches.get(u).stream()
                    .mapToInt(m -> (int) m.get("capacity")).sum();
            double workloadRatio = totalCapacity == 0
                    ? (playerCount == 0 ? 0.0 : Double.POSITIVE_INFINITY)
                    : (double) playerCount / totalCapacity;
            double quality = qualityFactor(playerCount, totalCapacity);
            String status = totalCapacity == 0 && playerCount > 0 ? "NO_COACH"
                    : (workloadRatio > 1.0 ? "OVERLOADED" : "OK");

            Map<String, Object> um = new LinkedHashMap<>();
            um.put("unit", u);
            um.put("players", unitPlayers.get(u));
            um.put("coaches", unitCoaches.get(u));
            um.put("playerCount", playerCount);
            um.put("totalCapacity", totalCapacity);
            um.put("workloadRatio", round2(workloadRatio));
            um.put("qualityFactor", round2(quality));
            um.put("status", status);
            um.put("explain", explainUnit(u, playerCount, totalCapacity, workloadRatio, quality, status));
            units.add(um);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("units", units);
        return out;
    }

    private String explainUnit(String unit, int players, int capacity,
                               double ratio, double quality, String status) {
        if ("NO_COACH".equals(status)) {
            return unit + ": " + players + " players but no assigned coach — training quality floored at "
                    + round2(quality) + "×. Assign a coach to this unit.";
        }
        if ("OVERLOADED".equals(status)) {
            return unit + ": " + players + " players vs capacity " + capacity + " (workload "
                    + round2(ratio) + "×). Quality reduced to " + round2(quality)
                    + "×. Add a coach or move players out.";
        }
        return unit + ": " + players + " players within capacity " + capacity + " (workload "
                + round2(ratio) + "×). Full quality " + round2(quality) + "×.";
    }

    /** Deterministic quality multiplier from load vs capacity. */
    private double qualityFactor(int players, int capacity) {
        if (players == 0) return 1.0;
        if (capacity == 0) return 0.5; // no coach
        double ratio = (double) players / capacity;
        if (ratio <= 1.0) return 1.0;
        return Math.max(0.5, 1.0 / ratio);
    }

    private int coachCapacity(Human coach, String unit) {
        int spec = specAttr(coach, unit); // 0-20
        return BASE_CAPACITY + (int) Math.round(MAX_CAPACITY_BONUS * (spec / 20.0));
    }

    private int specAttr(Human c, String unit) {
        return switch (unit) {
            case GK -> c.getCoachingGK();
            case DEF -> Math.max(c.getCoachingDefending(), c.getCoachingTactical());
            case ATT -> Math.max(c.getCoachingAttacking(), c.getCoachingTechnical());
            default -> c.getCoachingMental();
        };
    }

    private String specializationOf(Human c) {
        int gk = c.getCoachingGK(), def = c.getCoachingDefending(), att = c.getCoachingAttacking();
        int fit = c.getCoachingFitness();
        int max = Math.max(Math.max(gk, def), Math.max(att, fit));
        if (max == gk) return "Goalkeeping";
        if (max == def) return "Defending";
        if (max == att) return "Attacking";
        return "Fitness";
    }

    private String normalizeUnit(String unit) {
        if (unit == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unit required");
        String u = unit.trim().toUpperCase(Locale.ROOT);
        if (!UNITS.contains(u)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unit must be one of " + UNITS);
        }
        return u;
    }

    private double round2(double v) {
        if (Double.isInfinite(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }
}
