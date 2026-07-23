package com.footballmanagergamesimulator.squadplanner;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * Persistent Squad Planner: depth chart per canonical position across three
 * distinct season horizons (Current / Next / Season After), scoped per
 * (userId, teamId, seasonOffset).
 *
 * This service owns CRUD + coverage analysis. The deterministic assistant
 * lives in {@link SquadPlannerAssistant} and never overwrites locked slots.
 */
@Service
public class SquadPlannerService {

    /** Canonical position codes used for depth-chart grouping. */
    public static final List<String> CANONICAL_POSITIONS = List.of(
            "GK", "DR", "DC", "DL", "DM", "MC", "ML", "MR", "AMC", "AML", "AMR", "ST");

    /** Minimum filled depth per position before it is considered adequately covered. */
    public static final int MIN_DEPTH = 2;

    private static final long PLAYER_TYPE_ID = 1L; // Human.typeId for players

    private final SquadPlanSlotRepository slotRepository;
    private final HumanRepository humanRepository;
    private final GameStateService gameStateService;

    public SquadPlannerService(SquadPlanSlotRepository slotRepository,
                               HumanRepository humanRepository,
                               GameStateService gameStateService) {
        this.slotRepository = slotRepository;
        this.humanRepository = humanRepository;
        this.gameStateService = gameStateService;
    }

    /** Season index the horizon points at (current season + offset). */
    public int targetSeason(int seasonOffset) {
        return gameStateService.currentSeason() + clampOffset(seasonOffset);
    }

    private int clampOffset(int seasonOffset) {
        if (seasonOffset < 0 || seasonOffset > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seasonOffset must be 0, 1 or 2");
        }
        return seasonOffset;
    }

    public List<SquadPlanSlot> getPlan(long teamId, int seasonOffset) {
        clampOffset(seasonOffset);
        List<SquadPlanSlot> slots = slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset);
        slots.sort(Comparator.comparing(SquadPlanSlot::getPosition)
                .thenComparingInt(SquadPlanSlot::getDepthOrder));
        return slots;
    }

    /** Replace the whole horizon atomically (used by the bulk save from the UI). */
    public List<SquadPlanSlot> replacePlan(int userId, long teamId, int seasonOffset,
                                           List<SquadPlanSlot> incoming) {
        clampOffset(seasonOffset);
        int base = gameStateService.currentSeason();
        List<SquadPlanSlot> existing = slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset);
        slotRepository.deleteAll(existing);
        List<SquadPlanSlot> toSave = new ArrayList<>();
        for (SquadPlanSlot in : incoming) {
            SquadPlanSlot s = new SquadPlanSlot();
            s.setUserId(userId);
            s.setTeamId(teamId);
            s.setSeasonOffset(seasonOffset);
            s.setBaseSeason(base);
            s.setPosition(normalizePosition(in.getPosition()));
            s.setDepthOrder(in.getDepthOrder() <= 0 ? 1 : in.getDepthOrder());
            s.setPlayerId(in.getPlayerId());
            s.setRole(in.getRole());
            s.setRoleFamiliarity(in.getRoleFamiliarity());
            s.setPriority(in.getPriority());
            s.setPlannedSale(in.isPlannedSale());
            s.setPlannedLoan(in.isPlannedLoan());
            s.setYouthPromotion(in.isYouthPromotion());
            s.setRecruitmentNeed(in.isRecruitmentNeed());
            s.setLocked(in.isLocked());
            s.setNotes(in.getNotes());
            toSave.add(s);
        }
        return slotRepository.saveAll(toSave);
    }

    public SquadPlanSlot addSlot(int userId, long teamId, int seasonOffset, SquadPlanSlot in) {
        clampOffset(seasonOffset);
        SquadPlanSlot s = new SquadPlanSlot();
        s.setUserId(userId);
        s.setTeamId(teamId);
        s.setSeasonOffset(seasonOffset);
        s.setBaseSeason(gameStateService.currentSeason());
        s.setPosition(normalizePosition(in.getPosition()));
        s.setDepthOrder(in.getDepthOrder() <= 0 ? nextDepth(teamId, seasonOffset, in.getPosition()) : in.getDepthOrder());
        s.setPlayerId(in.getPlayerId());
        s.setRole(in.getRole());
        s.setRoleFamiliarity(in.getRoleFamiliarity());
        s.setPriority(in.getPriority());
        s.setPlannedSale(in.isPlannedSale());
        s.setPlannedLoan(in.isPlannedLoan());
        s.setYouthPromotion(in.isYouthPromotion());
        s.setRecruitmentNeed(in.isRecruitmentNeed());
        s.setLocked(in.isLocked());
        s.setNotes(in.getNotes());
        return slotRepository.save(s);
    }

    public SquadPlanSlot updateSlot(int userId, long slotId, SquadPlanSlot in) {
        SquadPlanSlot s = requireOwnedSlot(userId, slotId);
        // Optimistic-lock guard: if the client sends a version, reject stale writes.
        if (in.getVersion() != 0 && in.getVersion() != s.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot was modified concurrently");
        }
        s.setPosition(normalizePosition(in.getPosition()));
        if (in.getDepthOrder() > 0) s.setDepthOrder(in.getDepthOrder());
        s.setPlayerId(in.getPlayerId());
        s.setRole(in.getRole());
        s.setRoleFamiliarity(in.getRoleFamiliarity());
        s.setPriority(in.getPriority());
        s.setPlannedSale(in.isPlannedSale());
        s.setPlannedLoan(in.isPlannedLoan());
        s.setYouthPromotion(in.isYouthPromotion());
        s.setRecruitmentNeed(in.isRecruitmentNeed());
        s.setLocked(in.isLocked());
        s.setNotes(in.getNotes());
        return slotRepository.save(s);
    }

    public void removeSlot(int userId, long slotId) {
        SquadPlanSlot s = requireOwnedSlot(userId, slotId);
        slotRepository.delete(s);
    }

    private SquadPlanSlot requireOwnedSlot(int userId, long slotId) {
        SquadPlanSlot s = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found"));
        if (s.getUserId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your plan");
        }
        return s;
    }

    private int nextDepth(long teamId, int seasonOffset, String position) {
        String pos = normalizePosition(position);
        return slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset).stream()
                .filter(s -> pos.equals(s.getPosition()))
                .mapToInt(SquadPlanSlot::getDepthOrder)
                .max().orElse(0) + 1;
    }

    private String normalizePosition(String raw) {
        if (raw == null || raw.isBlank()) return "MC";
        String up = raw.trim().toUpperCase(Locale.ROOT);
        return CANONICAL_POSITIONS.contains(up) ? up : up;
    }

    /**
     * Coverage report for a horizon: positions below MIN_DEPTH, plus players
     * in the plan whose contract expires by the target season or who are on loan.
     */
    public Map<String, Object> analyzeCoverage(long teamId, int seasonOffset) {
        clampOffset(seasonOffset);
        int target = targetSeason(seasonOffset);
        List<SquadPlanSlot> slots = slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset);

        Map<Long, Human> playersById = new HashMap<>();
        for (Human h : humanRepository.findAllByTeamIdAndTypeId(teamId, PLAYER_TYPE_ID)) {
            playersById.put(h.getId(), h);
        }

        Map<String, Integer> filledDepth = new LinkedHashMap<>();
        for (String p : CANONICAL_POSITIONS) filledDepth.put(p, 0);
        List<Map<String, Object>> expiring = new ArrayList<>();
        List<Map<String, Object>> loaned = new ArrayList<>();

        for (SquadPlanSlot s : slots) {
            boolean effective = s.getPlayerId() != null && !s.isPlannedSale() && !s.isPlannedLoan();
            if (effective) {
                filledDepth.merge(s.getPosition(), 1, Integer::sum);
            }
            if (s.getPlayerId() != null) {
                Human h = playersById.get(s.getPlayerId());
                if (h != null) {
                    if (h.getContractEndSeason() > 0 && h.getContractEndSeason() <= target) {
                        expiring.add(playerRef(h, s));
                    }
                    String status = h.getCurrentStatus();
                    if (status != null && status.toLowerCase(Locale.ROOT).contains("loan")) {
                        loaned.add(playerRef(h, s));
                    }
                }
            }
        }

        List<Map<String, Object>> underCovered = new ArrayList<>();
        for (String p : CANONICAL_POSITIONS) {
            int depth = filledDepth.getOrDefault(p, 0);
            if (depth < MIN_DEPTH) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("position", p);
                m.put("depth", depth);
                m.put("shortfall", MIN_DEPTH - depth);
                underCovered.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("seasonOffset", seasonOffset);
        out.put("targetSeason", target);
        out.put("minDepth", MIN_DEPTH);
        out.put("filledDepth", filledDepth);
        out.put("underCoveredPositions", underCovered);
        out.put("expiringPlayers", expiring);
        out.put("loanedPlayers", loaned);
        return out;
    }

    private Map<String, Object> playerRef(Human h, SquadPlanSlot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId", h.getId());
        m.put("name", h.getName());
        m.put("position", s.getPosition());
        m.put("contractEndSeason", h.getContractEndSeason());
        m.put("currentStatus", h.getCurrentStatus());
        return m;
    }
}
