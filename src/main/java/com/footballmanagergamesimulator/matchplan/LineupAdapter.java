package com.footballmanagergamesimulator.matchplan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a team's selection into a {@link Lineup} of {@link Contributor}s, with an
 * explicit mode so the source is never inferred from data alone:
 *
 * <ul>
 *   <li>{@code USER_SAVED} — use the manager's saved {@code first11} verbatim
 *       (starters in slots &lt; 30, bench in slots 30-36) and invent NO
 *       substitutions. Malformed/incomplete/stale saved data falls back safely to
 *       the automatic selection with no invented subs, reported as
 *       {@code AUTO_FALLBACK}.</li>
 *   <li>{@code AI_INSTANT} — automatic best eleven + bench, with deterministic
 *       pre-planned substitutions.</li>
 * </ul>
 *
 * A {@link PersonalizedTactic} existing does NOT imply a user team — admin-edited
 * AI teams may have one — so the caller passes the mode explicitly.
 */
@Service
public class LineupAdapter {

    /** Pitch (starter) slots occupy 0-29; bench slots occupy 30-36. */
    private static final int PITCH_SLOT_MIN = 0;
    private static final int BENCH_SLOT_START = 30;
    private static final int BENCH_SLOT_MAX = 36;
    private static final int REQUIRED_STARTERS = 11;

    public enum Mode { USER_SAVED, AI_INSTANT }

    public enum Source { USER_SAVED, AI_INSTANT, AUTO_FALLBACK }

    public record Result(Lineup lineup, Source source) {}

    @Autowired @Lazy private TacticController tacticController;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private TacticService tacticService;

    /** Build a lineup for {@code teamId} in the given mode. */
    public Result build(long teamId, String tactic, long seed, Mode mode) {
        if (mode == Mode.USER_SAVED) {
            Lineup saved = tryBuildFromSavedXi(teamId);
            if (saved != null) {
                return new Result(saved, Source.USER_SAVED);
            }
            // Safe fallback: automatic selection, but NO invented subs for a user team.
            return new Result(buildAutomatic(teamId, tactic, seed, false), Source.AUTO_FALLBACK);
        }
        return new Result(buildAutomatic(teamId, tactic, seed, true), Source.AI_INSTANT);
    }

    /**
     * Build a lineup from already-selected {@link Contributor}s. The AI batch fast-path
     * feeds these from its warm per-round caches (best eleven, bench, squad skills), so
     * NO per-player query is issued here — the sole reason this seam exists is to keep
     * the fast-forward path query-free while still going through the one canonical
     * pipeline. The same deterministic {@link #simulateSubs} used by {@code AI_INSTANT}
     * is applied when {@code withSubs} is true, so a snapshot-fed lineup fields exactly
     * the pre-planned substitutions the canonical {@link ContributionResolver} reads.
     *
     * @param subSeed the already-mixed per-team seed (callers pass {@code planSeed*31+teamId}
     *                to match the {@code AI_INSTANT} derivation) so subs are reproducible.
     */
    public Lineup buildFromSnapshot(List<Contributor> startingXI, List<Contributor> bench,
                                    long subSeed, boolean withSubs) {
        List<Contributor> xi = startingXI != null ? startingXI : List.of();
        List<Contributor> benchList = bench != null ? bench : List.of();
        List<Lineup.SubMove> subs = withSubs ? simulateSubs(xi, benchList, subSeed) : List.of();
        return new Lineup(xi, benchList, subs);
    }

    // ---------------- USER_SAVED ----------------

    /**
     * Build strictly from the saved {@code first11}, or return null (caller falls
     * back) when the saved data is missing, malformed, or does not describe a valid
     * eleven of this team's players.
     */
    private Lineup tryBuildFromSavedXi(long teamId) {
        Optional<PersonalizedTactic> ptOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        if (ptOpt.isEmpty() || ptOpt.get().getFirst11() == null) return null;
        PersonalizedTactic pt = ptOpt.get();

        List<FormationData> formation;
        try {
            formation = objectMapper.readValue(pt.getFirst11(), new TypeReference<List<FormationData>>() {});
        } catch (Exception e) {
            return null; // malformed JSON
        }
        if (formation == null || formation.isEmpty()) return null;

        // (1) Validate the complete slot schema BEFORE sorting or resolving anything.
        // A null entry, non-positive player id, out-of-range slot, or duplicate slot
        // index is a corrupt snapshot: fall back as a whole, never throw or partial-build.
        Set<Integer> slotsSeen = new HashSet<>();
        for (FormationData d : formation) {
            if (d == null) return null;
            if (d.getPlayerId() <= 0) return null;
            int slot = d.getPositionIndex();
            if (slot < PITCH_SLOT_MIN || slot > BENCH_SLOT_MAX) return null; // negative / above bench
            if (!slotsSeen.add(slot)) return null;                           // duplicate slot index
        }

        // Sort by slot to preserve canonical order; split starters / bench.
        formation.sort((a, b) -> Integer.compare(a.getPositionIndex(), b.getPositionIndex()));
        List<FormationData> starterSlots = new ArrayList<>();
        List<FormationData> benchSlots = new ArrayList<>();
        for (FormationData d : formation) {
            (d.getPositionIndex() < BENCH_SLOT_START ? starterSlots : benchSlots).add(d);
        }
        if (starterSlots.size() != REQUIRED_STARTERS) return null; // incomplete XI

        Long penaltyTakerId = pt.getPenaltyTakerId();
        Long freeKickTakerId = pt.getFreeKickTakerId();

        // Resolve + validate: existing players of THIS team, no duplicates.
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> allIds = new ArrayList<>();
        starterSlots.forEach(d -> allIds.add(d.getPlayerId()));
        benchSlots.forEach(d -> allIds.add(d.getPlayerId()));
        Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(allIds).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, s -> s, (a, b) -> a));

        // (3) Starters are snapshotted in the position they were FIELDED (derived from the
        // grid slot), not their natural position, because ContributionResolver weights
        // scorer/assist by Contributor.position.
        List<Contributor> starters = new ArrayList<>();
        for (FormationData d : starterSlots) {
            String usedPosition = TacticService.getBasePosition(
                    tacticService.getPositionFromIndex(d.getPositionIndex()));
            Contributor c = resolveSavedPlayer(d.getPlayerId(), teamId, usedPosition,
                    skills, penaltyTakerId, freeKickTakerId, seen);
            if (c == null) return null; // missing / wrong team / duplicate / non-player → fall back
            starters.add(c);
        }
        // (2) The XI + bench snapshot is atomic: an invalid bench entry falls the whole
        // lineup back too. Bench players keep their natural position until live substitution
        // wiring supplies their actual on-pitch role.
        List<Contributor> bench = new ArrayList<>();
        for (FormationData d : benchSlots) {
            Contributor c = resolveSavedPlayer(d.getPlayerId(), teamId, null,
                    skills, penaltyTakerId, freeKickTakerId, seen);
            if (c == null) return null; // invalid bench entry → fall back as a whole
            bench.add(c);
        }

        return new Lineup(starters, bench, List.of()); // USER_SAVED: no invented subs
    }

    /**
     * Resolve one saved slot to a {@link Contributor}, or null when the entry is stale.
     * A valid entry is a non-retired {@link TypeNames#PLAYER_TYPE} Human of this team,
     * unique across the XI + bench. {@code forcedPosition} overrides the snapshotted
     * position (used for starters fielded out of their natural role); pass null to keep
     * the player's natural position (bench).
     */
    private Contributor resolveSavedPlayer(long playerId, long teamId, String forcedPosition,
                                           Map<Long, PlayerSkills> skills,
                                           Long penaltyTakerId, Long freeKickTakerId, Set<Long> seen) {
        if (!seen.add(playerId)) return null;                      // duplicate across XI + bench
        Optional<Human> ph = humanRepository.findById(playerId);
        if (ph.isEmpty()) return null;
        Human h = ph.get();
        if (h.getTypeId() != TypeNames.PLAYER_TYPE) return null;    // manager / staff id, not a player
        if (h.isRetired()) return null;                             // retired player
        if (h.getTeamId() == null || h.getTeamId() != teamId) return null; // team membership
        String position = forcedPosition != null ? forcedPosition : h.getPosition();
        PlayerSkills s = skills.get(playerId);
        return new Contributor(playerId, h.getName(), position, h.getRating(),
                s != null ? s.getFinishing() : 0, s != null ? s.getPassing() : 0,
                s != null ? s.getVision() : 0, h.getFitness(),
                penaltyTakerId != null && penaltyTakerId == playerId,
                freeKickTakerId != null && freeKickTakerId == playerId);
    }

    // ---------------- automatic (AI + fallback) ----------------

    private Lineup buildAutomatic(long teamId, String tactic, long seed, boolean withSubs) {
        List<PlayerView> xiViews = safe(tacticController.getBestEleven(String.valueOf(teamId), tactic));
        List<PlayerView> benchViews = safe(tacticController.getSubstitutions(String.valueOf(teamId), tactic));

        List<Long> ids = new ArrayList<>();
        xiViews.forEach(v -> ids.add(v.getId()));
        benchViews.forEach(v -> ids.add(v.getId()));
        Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(ids).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, s -> s, (a, b) -> a));

        Optional<PersonalizedTactic> pt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        Long penaltyTakerId = pt.map(PersonalizedTactic::getPenaltyTakerId).orElse(null);
        Long freeKickTakerId = pt.map(PersonalizedTactic::getFreeKickTakerId).orElse(null);

        List<Contributor> xi = xiViews.stream()
                .map(v -> toContributor(v, skills, penaltyTakerId, freeKickTakerId)).collect(Collectors.toList());
        List<Contributor> bench = benchViews.stream()
                .map(v -> toContributor(v, skills, penaltyTakerId, freeKickTakerId)).collect(Collectors.toList());

        List<Lineup.SubMove> subs = withSubs ? simulateSubs(xi, bench, seed * 31 + teamId) : List.of();
        return new Lineup(xi, bench, subs);
    }

    private Contributor toContributor(PlayerView v, Map<Long, PlayerSkills> skills,
                                      Long penaltyTakerId, Long freeKickTakerId) {
        PlayerSkills s = skills.get(v.getId());
        return new Contributor(
                v.getId(), v.getName(), v.getPosition(), v.getRating(),
                s != null ? s.getFinishing() : 0, s != null ? s.getPassing() : 0,
                s != null ? s.getVision() : 0, v.getFitness(),
                penaltyTakerId != null && penaltyTakerId == v.getId(),
                freeKickTakerId != null && freeKickTakerId == v.getId());
    }

    /**
     * Deterministic AI substitutions: an outfield starter is replaced by a bench
     * player of a compatible base-position group. Goalkeepers are excluded from the
     * incoming pool, so an outfielder is never replaced by a keeper.
     */
    private List<Lineup.SubMove> simulateSubs(List<Contributor> xi, List<Contributor> bench, long seed) {
        List<Contributor> outfieldStarters = xi.stream().filter(c -> !c.isGoalkeeper()).collect(Collectors.toList());
        List<Contributor> benchPool = bench.stream().filter(c -> !c.isGoalkeeper()).collect(Collectors.toList());
        if (outfieldStarters.isEmpty() || benchPool.isEmpty()) return List.of();

        Random rng = new Random(seed);
        Collections.shuffle(outfieldStarters, rng);
        Collections.shuffle(benchPool, rng);

        MatchEngineConfig.Events ev = engineConfig.getEvents();
        int count = Math.min(ev.getSubstitutionsPerTeam(), Math.min(outfieldStarters.size(), benchPool.size()));
        int minMin = ev.getSubstitutionMinuteMin();
        int minMax = ev.getSubstitutionMinuteMax();

        List<Integer> minutes = new ArrayList<>();
        for (int i = 0; i < count; i++) minutes.add(rng.nextInt(minMin, minMax));
        Collections.sort(minutes); // nondecreasing, so sequence order == minute order

        List<Contributor> availableBench = new ArrayList<>(benchPool);
        List<Lineup.SubMove> moves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Contributor off = outfieldStarters.get(i);
            Contributor on = pickCompatible(off, availableBench);
            if (on == null) break;
            availableBench.remove(on);
            moves.add(new Lineup.SubMove(i, minutes.get(i), off.playerId(), on));
        }
        return moves;
    }

    /** Prefer a bench player in the same base-position group; otherwise the first available. */
    private Contributor pickCompatible(Contributor off, List<Contributor> availableBench) {
        String group = baseGroup(off.position());
        for (Contributor c : availableBench) {
            if (baseGroup(c.position()).equals(group)) return c;
        }
        return availableBench.isEmpty() ? null : availableBench.get(0);
    }

    private String baseGroup(String position) {
        if (position == null) return "MID";
        return switch (position) {
            case "GK" -> "GK";
            case "DC", "DL", "DR" -> "DEF";
            case "DM", "MC", "ML", "MR" -> "MID";
            case "AMC", "AML", "AMR" -> "ATT_MID";
            case "ST" -> "ATT";
            default -> "MID";
        };
    }

    private List<PlayerView> safe(List<PlayerView> list) {
        return list != null ? list : List.of();
    }
}
