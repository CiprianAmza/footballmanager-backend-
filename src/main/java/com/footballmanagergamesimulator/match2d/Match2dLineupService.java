package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds what the 2D match engine needs to know about one team: who starts, in which
 * slot, with which attributes, under which tactic. Read-only: nothing is written.
 *
 * <p>The starting eleven is the one saved on the tactic when it is complete and valid;
 * otherwise the assistant's selection for the club's formation, the same fallback the
 * live match uses.
 */
@Service
public class Match2dLineupService {

    /** Grid slots 0..29 are on the pitch; 30 and above are the bench. */
    private static final int FIRST_BENCH_SLOT = 30;
    private static final int STARTERS = 11;
    private static final String GOALKEEPER = "GK";
    private static final String DEFAULT_FORMATION = "442";

    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final PlayerSkillsRepository playerSkillsRepository;
    private final PersonalizedTacticRepository personalizedTacticRepository;
    private final TacticService tacticService;
    private final TacticController tacticController;
    private final ObjectMapper objectMapper;

    public Match2dLineupService(TeamRepository teamRepository,
                                HumanRepository humanRepository,
                                PlayerSkillsRepository playerSkillsRepository,
                                PersonalizedTacticRepository personalizedTacticRepository,
                                TacticService tacticService,
                                @Lazy TacticController tacticController,
                                ObjectMapper objectMapper) {
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.playerSkillsRepository = playerSkillsRepository;
        this.personalizedTacticRepository = personalizedTacticRepository;
        this.tacticService = tacticService;
        this.tacticController = tacticController;
        this.objectMapper = objectMapper;
    }

    public Match2dTeam build(long teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Team " + teamId + " does not exist."));
        PersonalizedTactic tactic = personalizedTacticRepository
                .findPersonalizedTacticByTeamId(teamId).orElse(null);
        String formation = formationOf(teamId, tactic);

        List<FormationData> slots = savedStarters(teamId, tactic);
        Map<Long, Human> players = slots == null ? Map.of() : playersOf(teamId, slots);
        if (slots == null || players.size() != STARTERS) {
            // Nothing usable saved: field the assistant's eleven, as the live match does.
            slots = assistantStarters(team, formation);
            players = playersOf(teamId, slots);
            if (players.size() != STARTERS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        team.getName() + " cannot field eleven players.");
            }
        }

        Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(players.keySet())
                .stream().collect(Collectors.toMap(PlayerSkills::getPlayerId, Function.identity(),
                        (first, duplicate) -> first));

        List<Slot> eleven = new ArrayList<>(STARTERS);
        for (FormationData slot : slots) {
            Human player = players.get(slot.getPlayerId());
            PlayerSkills playerSkills = skills.get(slot.getPlayerId());
            if (playerSkills == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        player.getName() + " (" + team.getName() + ") has no attributes.");
            }
            eleven.add(new Slot(player, playerSkills,
                    tacticService.getPositionFromIndex(slot.getPositionIndex()), slot));
        }
        return assemble(team, formation, tactic, eleven);
    }

    /**
     * One starter as the engine receives him: the player, his attributes, the position of the
     * slot he plays in, and the role/duty/instructions saved for him ({@code roles} may be null).
     */
    public record Slot(Human player, PlayerSkills skills, String position, FormationData roles) {
    }

    /**
     * The team exactly as the engine receives it, from an eleven already chosen by the caller
     * (in the order given). Used by {@link #build} and by the round simulator, which sends the
     * same eleven the game's own engine fields.
     */
    public Match2dTeam assemble(Team team, String formation, PersonalizedTactic tactic, List<Slot> slots) {
        String shape = formation == null || formation.isBlank() ? DEFAULT_FORMATION : formation;
        List<Match2dTeam.Player> eleven = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            Human player = slot.player();
            FormationData roles = slot.roles();
            List<String> instructions = roles == null ? null : roles.getInstructions();
            eleven.add(new Match2dTeam.Player(
                    player.getId(),
                    player.getName(),
                    player.getShirtNumber(),
                    slot.position(),
                    player.getPosition(),
                    player.getRating(),
                    player.isStayForward(),
                    roles == null ? null : blankToNull(roles.getSpecialRole()),
                    roles == null ? null : blankToNull(roles.getRole()),
                    roles == null ? null : blankToNull(roles.getDuty()),
                    instructions == null || instructions.isEmpty() ? null : List.copyOf(instructions),
                    (roles != null && roles.isShadow()) || player.isStayForward(),
                    ratingsOf(slot.skills())));
        }
        return new Match2dTeam(team.getId(), team.getName(), shape,
                team.getColor1(), team.getColor2(), tacticOf(shape, tactic), eleven);
    }

    /** The club's formation code: the saved tactic, else the manager's preferred one, else 4-4-2. */
    private String formationOf(long teamId, PersonalizedTactic tactic) {
        if (tactic != null && tactic.getTactic() != null && !tactic.getTactic().isBlank()) {
            return tactic.getTactic();
        }
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(manager -> !manager.isRetired())
                .map(Human::getTacticStyle)
                .filter(style -> style != null && !style.isBlank())
                .findFirst().orElse(DEFAULT_FORMATION);
    }

    /**
     * The eleven starters saved on the tactic, left to right by grid slot, or {@code null}
     * when the saved line-up is missing, malformed or not a complete eleven with one keeper.
     */
    private List<FormationData> savedStarters(long teamId, PersonalizedTactic tactic) {
        if (tactic == null || tactic.getFirst11() == null || tactic.getFirst11().isBlank()) return null;
        List<FormationData> saved;
        try {
            saved = objectMapper.readValue(tactic.getFirst11(), new TypeReference<List<FormationData>>() {});
        } catch (Exception malformed) {
            return null;
        }
        return validStarters(saved);
    }

    private List<FormationData> assistantStarters(Team team, String formation) {
        List<FormationData> picked;
        try {
            picked = tacticController.askAssistant(team.getId(), formation);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No line-up could be picked for " + team.getName() + ": " + failure.getMessage());
        }
        List<FormationData> starters = validStarters(picked);
        if (starters == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    team.getName() + " cannot field eleven players.");
        }
        return starters;
    }

    /** Keeps the on-pitch slots; accepts them only as eleven distinct players with exactly one keeper. */
    private List<FormationData> validStarters(List<FormationData> all) {
        if (all == null) return null;
        List<FormationData> starters = all.stream()
                .filter(Objects::nonNull)
                .filter(slot -> slot.getPositionIndex() >= 0 && slot.getPositionIndex() < FIRST_BENCH_SLOT)
                .sorted(Comparator.comparingInt(FormationData::getPositionIndex))
                .toList();
        if (starters.size() != STARTERS) return null;
        Set<Long> ids = new HashSet<>();
        Set<Integer> usedSlots = new HashSet<>();
        int keepers = 0;
        for (FormationData slot : starters) {
            if (slot.getPlayerId() <= 0 || !ids.add(slot.getPlayerId())
                    || !usedSlots.add(slot.getPositionIndex())) return null;
            String position = tacticService.getPositionFromIndex(slot.getPositionIndex());
            if ("Unknown".equals(position)) return null;
            if (GOALKEEPER.equals(position)) keepers++;
        }
        return keepers == 1 ? starters : null;
    }

    /** The starters that really are active players of this club, by id. */
    private Map<Long, Human> playersOf(long teamId, List<FormationData> slots) {
        Set<Long> ids = slots.stream().map(FormationData::getPlayerId).collect(Collectors.toSet());
        Map<Long, Human> players = new LinkedHashMap<>();
        for (Human human : humanRepository.findAllById(ids)) {
            if (ids.contains(human.getId())
                    && human.getTypeId() == TypeNames.PLAYER_TYPE && !human.isRetired()
                    && human.getTeamId() != null && human.getTeamId() == teamId) {
                players.put(human.getId(), human);
            }
        }
        return players;
    }

    private static Map<String, Object> tacticOf(String formation, PersonalizedTactic tactic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formation", formation);
        if (tactic == null) return out;
        put(out, "mentality", tactic.getMentality());
        put(out, "tempo", tactic.getTempo());
        put(out, "passingType", tactic.getPassingType());
        put(out, "defensiveLine", tactic.getDefensiveLine());
        put(out, "pressing", tactic.getPressing());
        put(out, "width", tactic.getWidth());
        put(out, "recovery", tactic.getRecovery());
        put(out, "inPossession", tactic.getInPossession());
        put(out, "timeWasting", tactic.getTimeWasting());
        put(out, "dribbling", tactic.getDribbling());
        put(out, "foulFrequency", tactic.getFoulFrequency());
        put(out, "foulHardness", tactic.getFoulHardness());
        put(out, "tempoFragmentation", tactic.getTempoFragmentation());
        put(out, "widePlay", tactic.getWidePlay());
        put(out, "transition", tactic.getTransition());
        put(out, "penaltyTakerId", tactic.getPenaltyTakerId());
        put(out, "freeKickTakerId", tactic.getFreeKickTakerId());
        put(out, "cornerTakerLeftId", tactic.getCornerTakerLeftId());
        put(out, "cornerTakerRightId", tactic.getCornerTakerRightId());
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value instanceof String text ? !text.isBlank() : value != null) out.put(key, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Every attribute, under the name the engine knows it by (the {@code PlayerSkills} field name). */
    static Map<String, Integer> ratingsOf(PlayerSkills s) {
        Map<String, Integer> r = new LinkedHashMap<>();
        // technical
        r.put("corners", s.getCorners());
        r.put("crossing", s.getCrossing());
        r.put("dribbling", s.getDribbling());
        r.put("finishing", s.getFinishing());
        r.put("firstTouch", s.getFirstTouch());
        r.put("freeKick", s.getFreeKick());
        r.put("heading", s.getHeading());
        r.put("longShots", s.getLongShots());
        r.put("longThrows", s.getLongThrows());
        r.put("marking", s.getMarking());
        r.put("passing", s.getPassing());
        r.put("penaltyTaking", s.getPenaltyTaking());
        r.put("tackling", s.getTackling());
        r.put("ballRecovery", s.getBallRecovery());
        r.put("technique", s.getTechnique());
        // mental
        r.put("aggression", s.getAggression());
        r.put("anticipation", s.getAnticipation());
        r.put("bravery", s.getBravery());
        r.put("composure", s.getComposure());
        r.put("concentration", s.getConcentration());
        r.put("decisions", s.getDecisions());
        r.put("determination", s.getDetermination());
        r.put("flair", s.getFlair());
        r.put("leadership", s.getLeadership());
        r.put("offTheBall", s.getOffTheBall());
        r.put("positioning", s.getPositioning());
        r.put("teamwork", s.getTeamwork());
        r.put("vision", s.getVision());
        r.put("workRate", s.getWorkRate());
        // physical
        r.put("acceleration", s.getAcceleration());
        r.put("agility", s.getAgility());
        r.put("balance", s.getBalance());
        r.put("jumpingReach", s.getJumpingReach());
        r.put("naturalFitness", s.getNaturalFitness());
        r.put("pace", s.getPace());
        r.put("stamina", s.getStamina());
        r.put("strength", s.getStrength());
        // goalkeeping
        r.put("handling", s.getHandling());
        r.put("reflexes", s.getReflexes());
        r.put("oneOnOnes", s.getOneOnOnes());
        r.put("commandOfArea", s.getCommandOfArea());
        r.put("kicking", s.getKicking());
        r.put("throwing", s.getThrowing());
        return r;
    }
}
