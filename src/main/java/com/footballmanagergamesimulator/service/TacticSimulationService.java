package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tactics Advisor and custom competition simulator.
 *
 * <p>Every advisor and custom-competition path uses the authoritative Compartment Engine pipeline.
 */
@Service
public class TacticSimulationService {

    private static final long BASE_SEED = 20260528L;
    private static final String DEFAULT_FORMATION = "442";
    private static final int MAX_SEASONS = 10_000;

    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private HumanRepository humanRepo;
    @Autowired private GameStateService gameState;
    @Autowired private TacticController tacticController;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private TacticService tacticService;
    @Autowired private CanonicalRuntimeInputFactory canonicalRuntimeInputFactory;
    @Autowired private CanonicalScoreSampler canonicalScoreSampler;
    @Autowired private CompartmentEngineConfig compartmentEngineConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private ChairmanTacticalMandateEnforcementService mandateEnforcement;

    public record TacticPointsRow(String mentality, String tempo, String passingType,
                                  String inPossession, String timeWasting,
                                  String defensiveLine, String pressing, String width,
                                  String dribbling, String foulFrequency, String foulHardness,
                                  String tempoFragmentation, String widePlay, String transition, String recovery,
                                  double avgPoints, int minPoints, int maxPoints) {}

    public record TacticPointsResult(long teamId, String teamName, String formation,
                                     int seasons, int opponentCount, List<TacticPointsRow> rows) {}

    public record StandingRow(long teamId, String teamName, int played, int wins, int draws,
                              int losses, int goalsFor, int goalsAgainst, int points) {}

    public record CompetitionResult(List<StandingRow> standings) {}

    /** Authoritative score for a standalone fixture such as a friendly. */
    public record CanonicalStandaloneScore(int homeGoals, int awayGoals,
                                           double homePower, double awayPower,
                                           CanonicalMatchEvaluation evaluation) {}

    public CanonicalStandaloneScore scoreCanonicalMatch(long homeTeamId, long awayTeamId,
                                                         String fixtureKey, long competitionId,
                                                         int season, int round) {
        if (homeTeamId <= 0 || awayTeamId <= 0 || homeTeamId == awayTeamId) {
            throw new IllegalArgumentException("standalone team ids must be positive and distinct");
        }
        if (fixtureKey == null || fixtureKey.isBlank()) {
            throw new IllegalArgumentException("fixtureKey must not be blank");
        }
        CanonicalFormationEvaluation home = currentCanonicalFormation(homeTeamId);
        CanonicalFormationEvaluation away = currentCanonicalFormation(awayTeamId);
        CanonicalMatchEvaluation evaluation = canonicalAdapter().evaluate(
                home.evaluation(), away.evaluation(), MatchVenue.HOME);
        long seed = MatchPlanService.seedFor(fixtureKey, competitionId, season, round,
                homeTeamId, awayTeamId);
        CanonicalScoreSampler.GoalSample goals = canonicalScoreSampler.sample(evaluation, seed);
        return new CanonicalStandaloneScore(
                goals.homeGoals(), goals.awayGoals(),
                home.evaluation().team().attack() + home.evaluation().team().attackProtection(),
                away.evaluation().team().attack() + away.evaluation().team().attackProtection(),
                evaluation);
    }

    private CanonicalFormationEvaluation currentCanonicalFormation(long teamId) {
        PersonalizedTactic tactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId)
                .map(TacticSimulationService::copyTacticAxes)
                .orElseGet(TacticSimulationService::neutralCanonicalTactic);
        PersonalizedTactic persisted = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId)
                .orElse(null);
        String formation = persisted == null ? null : persisted.getTactic();
        return formation == null || formation.isBlank()
                ? bestCanonicalFormation(teamId, tactic)
                : canonicalFormation(teamId, formation, tactic);
    }

    /** Fully loaded canonical inputs; player capabilities are read once, then tactic contexts vary in memory. */
    private record CanonicalAdvisorMatchup(String formation, CanonicalRuntimeTeamInput team,
                                           CanonicalRuntimeTeamInput[] opponents, int oppCount) {}

    private CanonicalAdvisorMatchup buildCanonicalAdvisorMatchup(long teamId, String formation,
                                                                  List<Long> opponentIds) {
        String form = (formation == null || formation.isBlank()) ? DEFAULT_FORMATION : formation;
        List<Long> ids = resolveOpponents(teamId, gameState.currentSeason(), opponentIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("Tactics Advisor needs at least one opponent");
        CanonicalRuntimeTeamInput team = canonicalTeamInput(teamId, form, neutralCanonicalTactic());

        CanonicalRuntimeTeamInput[] opponents = new CanonicalRuntimeTeamInput[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            long opponentId = ids.get(i);
            String opponentFormation = advisorFormationFor(opponentId);
            PersonalizedTactic tactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(opponentId)
                    .map(TacticSimulationService::copyTacticAxes)
                    .orElseGet(TacticSimulationService::neutralCanonicalTactic);
            opponents[i] = canonicalTeamInput(opponentId, opponentFormation, tactic);
        }
        return new CanonicalAdvisorMatchup(form, team, opponents, opponents.length);
    }

    /** Rank all canonical tactic-axis combinations by deterministic Compartment score sampling. */
    public TacticPointsResult simulateTacticPoints(long teamId, String formation, int seasons,
                                                   List<Long> opponentIds) {
        int n = Math.max(1, Math.min(MAX_SEASONS, seasons <= 0 ? 10 : seasons));
        CanonicalAdvisorMatchup m = buildCanonicalAdvisorMatchup(teamId, formation, opponentIds);
        CanonicalMatchEvaluationAdapter adapter = canonicalAdapter();
        CanonicalTeamEvaluation[] opponentEvaluations = canonicalOpponentEvaluations(adapter, m);

        List<TacticPointsRow> rows = new ArrayList<>();
        for (PersonalizedTactic t : canonicalAdvisorTactics()) {
            CanonicalRuntimeTeamInput candidate = canonicalRuntimeInputFactory.withTactic(m.team(), t);
            CanonicalTeamEvaluation candidateEvaluation = adapter.evaluateTeam(candidate);
            CanonicalMatchEvaluation[] homeLegs = new CanonicalMatchEvaluation[m.oppCount()];
            CanonicalMatchEvaluation[] awayLegs = new CanonicalMatchEvaluation[m.oppCount()];
            for (int o = 0; o < m.oppCount(); o++) {
                homeLegs[o] = adapter.evaluate(candidateEvaluation, opponentEvaluations[o], MatchVenue.HOME);
                awayLegs[o] = adapter.evaluate(opponentEvaluations[o], candidateEvaluation, MatchVenue.HOME);
            }
            int[] minMax = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            long sum = 0;
            for (int s = 0; s < n; s++) {
                int pts = 0;
                for (int o = 0; o < m.oppCount(); o++) {
                    long pairedSeed = BASE_SEED + (long) s * Math.max(1, m.oppCount()) * 2L + o * 2L;
                    CanonicalScoreSampler.GoalSample home = canonicalScoreSampler.sample(homeLegs[o], pairedSeed);
                    CanonicalScoreSampler.GoalSample away = canonicalScoreSampler.sample(awayLegs[o], pairedSeed + 1L);
                    pts += points(home.homeGoals(), home.awayGoals());
                    pts += points(away.awayGoals(), away.homeGoals());
                }
                sum += pts;
                if (pts < minMax[0]) minMax[0] = pts;
                if (pts > minMax[1]) minMax[1] = pts;
            }
            rows.add(new TacticPointsRow(t.getMentality(), t.getTempo(), t.getPassingType(),
                    t.getInPossession(), t.getTimeWasting(),
                    t.getDefensiveLine(), t.getPressing(), t.getWidth(),
                    t.getDribbling(), t.getFoulFrequency(), t.getFoulHardness(),
                    t.getTempoFragmentation(), t.getWidePlay(), t.getTransition(), t.getRecovery(),
                    sum / (double) n, minMax[0], minMax[1]));
        }
        rows.sort(Comparator.comparingDouble(TacticPointsRow::avgPoints).reversed());

        // Keep only the distinct average-points values (as displayed, 2 decimals); on a tie, prefer
        // the row with the highest MINIMUM points (best worst-case). Many tactics score identically,
        // so this collapses the list to the genuinely different outcomes.
        Map<String, TacticPointsRow> byAvg = new LinkedHashMap<>();
        for (TacticPointsRow r : rows) {
            String key = String.format("%.2f", r.avgPoints());
            TacticPointsRow cur = byAvg.get(key);
            if (cur == null || r.minPoints() > cur.minPoints()) byAvg.put(key, r);
        }
        List<TacticPointsRow> distinct = new ArrayList<>(byAvg.values());
        distinct.sort(Comparator.comparingDouble(TacticPointsRow::avgPoints).reversed());

        String name = teamRepo.findNameById(teamId);
        return new TacticPointsResult(teamId, name == null ? "Team#" + teamId : name,
                m.formation(), n, m.oppCount(), distinct);
    }

    public record AnalyticalRow(String mentality, String tempo, String passingType, String inPossession,
                                String timeWasting, String defensiveLine, String pressing, String width,
                                String dribbling, String foulFrequency, String foulHardness,
                                String tempoFragmentation, String widePlay, String transition, String recovery,
                                double expectedPoints, double expectedGoalDifference,
                                double winProbability, double drawProbability, double lossProbability) {}

    public record AnalyticalResult(long teamId, String teamName, String formation, int opponentCount,
                                   List<AnalyticalRow> rows) {}

    /**
     * Analytical advisor ranking against the team's real league opponents through the authoritative
     * canonical evaluation (player attributes, position/role familiarity, morale, fitness, tactical
     * context, compartments and goal PMFs). Same opponents and tactics as the simulated tab.
     * Distinct expected-points values only (tie → higher goal difference); capped at {@code topN}.
     */
    public AnalyticalResult analyticalTacticPoints(long teamId, String formation, int topN,
                                                   List<Long> opponentIds) {
        CanonicalAdvisorMatchup m = buildCanonicalAdvisorMatchup(teamId, formation, opponentIds);
        CanonicalMatchEvaluationAdapter adapter = canonicalAdapter();
        CanonicalTeamEvaluation[] opponentEvaluations = canonicalOpponentEvaluations(adapter, m);
        double games = Math.max(1, 2 * m.oppCount());

        List<AnalyticalRow> rows = new ArrayList<>();
        for (PersonalizedTactic t : canonicalAdvisorTactics()) {
            CanonicalRuntimeTeamInput candidate = canonicalRuntimeInputFactory.withTactic(m.team(), t);
            CanonicalTeamEvaluation candidateEvaluation = adapter.evaluateTeam(candidate);
            double pts = 0, xgd = 0, wins = 0, draws = 0, losses = 0;
            for (int o = 0; o < m.oppCount(); o++) {
                CanonicalMatchEvaluation home = adapter.evaluate(candidateEvaluation, opponentEvaluations[o], MatchVenue.HOME);
                CanonicalMatchEvaluation away = adapter.evaluate(opponentEvaluations[o], candidateEvaluation, MatchVenue.HOME);
                pts += expectedHomePoints(home) + expectedAwayPoints(away);
                xgd += home.probability().homeXg() - home.probability().awayXg();
                xgd += away.probability().awayXg() - away.probability().homeXg();
                wins += home.outcome().homeWin() + away.outcome().awayWin();
                draws += home.outcome().draw() + away.outcome().draw();
                losses += home.outcome().awayWin() + away.outcome().homeWin();
            }
            rows.add(new AnalyticalRow(t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(),
                    t.getTimeWasting(), t.getDefensiveLine(), t.getPressing(), t.getWidth(), t.getDribbling(),
                    t.getFoulFrequency(), t.getFoulHardness(), t.getTempoFragmentation(), t.getWidePlay(),
                    t.getTransition(), t.getRecovery(), pts, xgd / games,
                    wins / games, draws / games, losses / games));
        }
        rows.sort(Comparator.comparingDouble(AnalyticalRow::expectedPoints).reversed());

        // Distinct expected-points values (2dp); on a tie, prefer the higher goal difference.
        Map<String, AnalyticalRow> byPts = new LinkedHashMap<>();
        for (AnalyticalRow r : rows) {
            String key = String.format("%.2f", r.expectedPoints());
            AnalyticalRow cur = byPts.get(key);
            if (cur == null || r.expectedGoalDifference() > cur.expectedGoalDifference()) byPts.put(key, r);
        }
        List<AnalyticalRow> distinct = new ArrayList<>(byPts.values());
        distinct.sort(Comparator.comparingDouble(AnalyticalRow::expectedPoints).reversed());
        if (topN > 0 && distinct.size() > topN) distinct = new ArrayList<>(distinct.subList(0, topN));

        String name = teamRepo.findNameById(teamId);
        return new AnalyticalResult(teamId, name == null ? "Team#" + teamId : name,
                m.formation(), m.oppCount(), distinct);
    }

    private CanonicalMatchEvaluationAdapter canonicalAdapter() {
        return new CanonicalMatchEvaluationAdapter(compartmentEngineConfig, matchEngineConfig);
    }

    /**
     * Select the best formation using only the persisted 1..300 {@link Human#getRating()} values:
     * for every formation, put the highest-rated natural player in each required position, fill
     * any remaining slots by rating, sum the selected eleven, and take the greatest sum. Engine
     * weights, attributes, morale, fitness, roles and tactical axes cannot influence this choice.
     * They are applied only after the formation and XI have been frozen, when the selected lineup
     * is evaluated by the canonical Compartment pipeline.
     *
     * <p>Chairman formation mandates are still applied before candidate selection. The method is
     * read-only and does not mutate manager preferences or the
     * {@code alwaysUseBestPossibleTactic} flag.</p>
     */
    /**
     * Evaluates one named formation under one tactic, without picking a winner.
     *
     * <p>{@link #bestCanonicalFormation} chooses by {@code topXiRating} — the raw sum of
     * the eleven's 1&ndash;300 ratings — which asks "where can I field my best-rated
     * players", not "which shape wins matches". Those diverge: the compartment engine
     * scores three weighted compartments and a defensive exposure model, none of which a
     * rating sum can express. Anything searching for a genuinely best setup has to be able
     * to price a formation it would not otherwise have chosen.
     */
    public CanonicalFormationEvaluation canonicalFormation(long teamId, String formation,
                                                           PersonalizedTactic tactic) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId must be positive");
        PersonalizedTactic effectiveTactic = tactic == null ? neutralCanonicalTactic() : tactic;
        RatingSelectedCanonicalInput selected =
                canonicalTeamInputByRawRating(teamId, formation, effectiveTactic);
        return new CanonicalFormationEvaluation(formation, selected.input(),
                canonicalAdapter().evaluateTeam(selected.input()), selected.ratingTotal());
    }

    public CanonicalFormationEvaluation bestCanonicalFormation(long teamId, PersonalizedTactic tactic) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId must be positive");
        PersonalizedTactic effectiveTactic = tactic == null ? neutralCanonicalTactic() : tactic;
        CanonicalMatchEvaluationAdapter adapter = canonicalAdapter();
        List<String> formations = tacticService.getAllExistingTactics().stream()
                .map(formation -> mandateEnforcement.effectiveFormation(teamId, formation))
                .distinct()
                .sorted()
                .toList();
        if (formations.isEmpty()) {
            formations = List.of(mandateEnforcement.effectiveFormation(teamId, DEFAULT_FORMATION));
        }

        CanonicalFormationEvaluation best = null;
        for (String formation : formations) {
            RatingSelectedCanonicalInput selected =
                    canonicalTeamInputByRawRating(teamId, formation, effectiveTactic);
            CanonicalRuntimeTeamInput input = selected.input();
            CanonicalTeamEvaluation evaluation = adapter.evaluateTeam(input);
            CanonicalFormationEvaluation candidate =
                    new CanonicalFormationEvaluation(formation, input, evaluation, selected.ratingTotal());
            if (best == null || candidate.topXiRating() > best.topXiRating()
                    || (Double.compare(candidate.topXiRating(), best.topXiRating()) == 0
                    && candidate.formation().compareTo(best.formation()) < 0)) {
                best = candidate;
            }
        }
        return java.util.Objects.requireNonNull(best, "best canonical formation");
    }

    private RatingSelectedCanonicalInput canonicalTeamInputByRawRating(
            long teamId, String proposedFormation, PersonalizedTactic tactic) {
        String formation = mandateEnforcement.effectiveFormation(teamId, proposedFormation);
        List<String> requiredSlots = new ArrayList<>(11);
        tacticService.getRoomInTeamByTactic(formation).entrySet().stream()
                .sorted(Comparator.comparingInt(entry ->
                        tacticService.getValueForTacticDisplay(entry.getKey())))
                .forEach(entry -> {
                    for (int occurrence = 0; occurrence < entry.getValue(); occurrence++) {
                        requiredSlots.add(entry.getKey());
                    }
                });
        if (requiredSlots.size() != 11) {
            throw new IllegalArgumentException("Formation " + formation + " does not contain exactly 11 slots");
        }

        Comparator<Human> ratingOrder = Comparator.comparingDouble(Human::getRating).reversed()
                .thenComparingLong(Human::getId);
        List<Human> remaining = humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .sorted(ratingOrder)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<RatingSelectedSlot> selected = new ArrayList<>(11);
        Map<Long, FormationData> savedData = savedFormationData(teamId, formation);
        Set<Integer> expectedIndexes = Arrays.stream(tacticService.getFormationGridIndicesExact(formation))
                .boxed().collect(java.util.stream.Collectors.toSet());
        Set<Integer> savedIndexes = savedData.values().stream()
                .map(FormationData::getPositionIndex)
                .collect(java.util.stream.Collectors.toSet());
        boolean completeSavedEleven = savedData.size() == 11 && savedIndexes.equals(expectedIndexes);
        if (completeSavedEleven) {
            for (FormationData row : savedData.values().stream()
                    .sorted(Comparator.comparingInt(FormationData::getPositionIndex)).toList()) {
                Human player = remaining.stream().filter(candidate -> candidate.getId() == row.getPlayerId())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "Saved starter " + row.getPlayerId() + " is not available for team " + teamId));
                String usedPosition = tacticService.getPositionFromIndex(row.getPositionIndex());
                if (!requiredSlots.remove(usedPosition)) {
                    throw new IllegalArgumentException("Saved slot " + row.getPositionIndex()
                            + " is not part of formation " + formation);
                }
                remaining.remove(player);
                selected.add(new RatingSelectedSlot(player, usedPosition));
            }
        } else {
            // An incomplete saved XI is not authoritative, but an explicitly designated
            // SHOOTER must still be fixed into his slot while the remaining ten are selected.
            FormationData savedShooter = savedData.values().stream()
                    .filter(row -> row.getPositionIndex() < 30 && "SHOOTER".equalsIgnoreCase(row.getSpecialRole()))
                    .findFirst().orElse(null);
            if (savedShooter != null) {
                Human shooter = remaining.stream().filter(player -> player.getId() == savedShooter.getPlayerId())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "Saved SHOOTER is not available for team " + teamId));
                String shooterSlot = tacticService.getPositionFromIndex(savedShooter.getPositionIndex());
                if (!requiredSlots.remove(shooterSlot)) {
                    throw new IllegalArgumentException("Saved SHOOTER slot is not part of formation " + formation);
                }
                remaining.remove(shooter);
                selected.add(new RatingSelectedSlot(shooter, shooterSlot));
            }
        }
        List<String> unfilled = new ArrayList<>();
        for (String slot : requiredSlots) {
            // Exact position first, then the same family — a DM is an MC, an AML is an ML.
            // Matching only on the exact string sent naturals to the bench and left the slot
            // to be back-filled by whoever was left over, which is how a defender ended up
            // at AMC scoring 21 against team-mates on 120.
            Human bestNatural = remaining.stream()
                    .filter(player -> slot.equals(player.getPosition()))
                    .min(ratingOrder)
                    .orElseGet(() -> remaining.stream()
                            .filter(player -> sameFamily(player.getPosition(), slot))
                            .min(ratingOrder)
                            .orElse(null));
            if (bestNatural == null) {
                unfilled.add(slot);
            } else {
                remaining.remove(bestNatural);
                selected.add(new RatingSelectedSlot(bestNatural, slot));
            }
        }
        if (remaining.size() < unfilled.size()) {
            throw new IllegalArgumentException("Team " + teamId + " does not have 11 players for " + formation);
        }
        // What is left cannot play the slot naturally, so take whoever loses the least by
        // being moved there rather than whoever is best on paper. Raw rating picked the
        // strongest defender for a hole in attack; his rating counts what he does at the
        // back, and the engine then scores him on a compartment he contributes nothing to.
        for (String slot : unfilled) {
            Human best = remaining.stream()
                    .max(Comparator.comparingDouble(player -> effectiveRatingInSlot(player, slot)))
                    .orElseThrow();
            remaining.remove(best);
            selected.add(new RatingSelectedSlot(best, slot));
        }
        selected.sort(Comparator.<RatingSelectedSlot>comparingInt(slot ->
                        tacticService.getValueForTacticDisplay(slot.usedPosition()))
                .thenComparing(slot -> slot.player().getId()));

        List<Long> playerIds = selected.stream().map(slot -> slot.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        playerSkillsRepository.findAllByPlayerIdIn(playerIds)
                .forEach(row -> skills.put(row.getPlayerId(), row));
        Map<PlayerPosition, Integer> occurrences = new java.util.EnumMap<>(PlayerPosition.class);
        List<RuntimeLineupSlot> runtimeSlots = new ArrayList<>(11);
        double ratingTotal = 0.0;
        for (RatingSelectedSlot slot : selected) {
            Human player = slot.player();
            PlayerSkills playerSkills = skills.get(player.getId());
            if (playerSkills == null) {
                throw new IllegalArgumentException("Missing canonical skills for player " + player.getId());
            }
            PlayerPosition position = PlayerPosition.require(slot.usedPosition());
            int occurrence = occurrences.merge(position, 1, Integer::sum);
            runtimeSlots.add(new RuntimeLineupSlot(player, playerSkills, savedData.get(player.getId()),
                    position, occurrence));
            // Weighted by how well the man fits the slot, so a formation that cannot be
            // staffed scores as the compromise it is. A raw sum treated a defender at AMC
            // as worth his full rating, which made 4321 look best for a squad holding one
            // natural AMC — and the engine then scored that slot at a fifth of its
            // team-mates.
            ratingTotal += effectiveRatingInSlot(player, slot.usedPosition());
        }
        return new RatingSelectedCanonicalInput(
                canonicalRuntimeInputFactory.build(tactic, runtimeSlots), ratingTotal);
    }

    /** Same base position, so the move costs nothing: DM/MC/AMC are one family, ML/AML another. */
    private boolean sameFamily(String naturalPosition, String slot) {
        String natural = TacticService.getBasePosition(naturalPosition);
        String target = TacticService.getBasePosition(slot);
        return natural != null && natural.equals(target);
    }

    /** A player's rating discounted by how far the slot is from his own position. */
    private double effectiveRatingInSlot(Human player, String slot) {
        if (sameFamily(player.getPosition(), slot)) return player.getRating();
        return player.getRating() * playerValueService.familiarityFactor(player.getPosition(), slot);
    }

    private record RatingSelectedSlot(Human player, String usedPosition) {}

    private record RatingSelectedCanonicalInput(CanonicalRuntimeTeamInput input, double ratingTotal) {}

    public record CanonicalFormationEvaluation(String formation,
                                               CanonicalRuntimeTeamInput input,
                                               CanonicalTeamEvaluation evaluation,
                                               double topXiRating) {
        public CanonicalFormationEvaluation {
            if (formation == null || formation.isBlank()) {
                throw new IllegalArgumentException("formation must not be blank");
            }
            java.util.Objects.requireNonNull(input, "input");
            java.util.Objects.requireNonNull(evaluation, "evaluation");
            if (!Double.isFinite(topXiRating) || topXiRating < 0) {
                throw new IllegalArgumentException("topXiRating must be finite and non-negative");
            }
        }
    }

    private static CanonicalTeamEvaluation[] canonicalOpponentEvaluations(
            CanonicalMatchEvaluationAdapter adapter, CanonicalAdvisorMatchup matchup) {
        CanonicalTeamEvaluation[] result = new CanonicalTeamEvaluation[matchup.oppCount()];
        for (int i = 0; i < result.length; i++) result[i] = adapter.evaluateTeam(matchup.opponents()[i]);
        return result;
    }

    private static double expectedHomePoints(CanonicalMatchEvaluation evaluation) {
        return 3.0 * evaluation.outcome().homeWin() + evaluation.outcome().draw();
    }

    private static double expectedAwayPoints(CanonicalMatchEvaluation evaluation) {
        return 3.0 * evaluation.outcome().awayWin() + evaluation.outcome().draw();
    }

    private static int points(int goalsFor, int goalsAgainst) {
        return goalsFor > goalsAgainst ? 3 : goalsFor == goalsAgainst ? 1 : 0;
    }

    /** Every distinct canonical tactic state, including recovery only where it can activate PASSING STYLE. */
    private static List<PersonalizedTactic> canonicalAdvisorTactics() {
        List<PersonalizedTactic> result = new ArrayList<>(4_050);
        for (String mentality : MatchEngineConfig.TacticalModel.MENTALITY_OPTIONS)
            for (String tempo : MatchEngineConfig.TacticalModel.TEMPO_OPTIONS)
                for (String passing : MatchEngineConfig.TacticalModel.PASSING_OPTIONS)
                    for (String line : MatchEngineConfig.TacticalModel.DEFENSIVE_LINE_OPTIONS)
                        for (String pressing : MatchEngineConfig.TacticalModel.PRESSING_OPTIONS)
                            for (String width : MatchEngineConfig.TacticalModel.WIDTH_OPTIONS) {
                              List<String> recoveries = "Short".equals(passing) && "Aggressive".equals(pressing)
                                      ? MatchEngineConfig.TacticalModel.RECOVERY_OPTIONS : List.of("Standard");
                              for (String recovery : recoveries) {
                                PersonalizedTactic tactic = neutralCanonicalTactic();
                                tactic.setMentality(mentality);
                                tactic.setTempo(tempo);
                                tactic.setPassingType(passing);
                                tactic.setDefensiveLine(line);
                                tactic.setPressing(pressing);
                                tactic.setWidth(width);
                                tactic.setRecovery(recovery);
                                result.add(tactic);
                              }
                            }
        return result;
    }

    private static PersonalizedTactic neutralCanonicalTactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Balanced");
        tactic.setTempo("Standard");
        tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard");
        tactic.setPressing("Normal");
        tactic.setWidth("Balanced");
        // Kept for the existing DTO/UI only; these fields are intentionally not scored by V1.
        tactic.setInPossession("Standard");
        tactic.setTimeWasting("Never");
        tactic.setDribbling("Standard");
        tactic.setFoulFrequency("Normal");
        tactic.setFoulHardness("Medium");
        tactic.setTempoFragmentation("Normal");
        tactic.setWidePlay("Shoot");
        tactic.setTransition("Balanced");
        tactic.setRecovery("Standard");
        return tactic;
    }

    private static PersonalizedTactic copyTacticAxes(PersonalizedTactic source) {
        PersonalizedTactic copy = neutralCanonicalTactic();
        copy.setMentality(source.getMentality());
        copy.setTempo(source.getTempo());
        copy.setPassingType(source.getPassingType());
        copy.setDefensiveLine(source.getDefensiveLine());
        copy.setPressing(source.getPressing());
        copy.setWidth(source.getWidth());
        copy.setRecovery(source.getRecovery());
        return copy;
    }

    private CanonicalRuntimeTeamInput canonicalTeamInput(long teamId, String formation,
                                                          PersonalizedTactic tactic) {
        List<TacticController.StarterSlot> selected =
                tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        if (selected.size() != 11) {
            throw new IllegalArgumentException("Team " + teamId + " does not have a complete XI for " + formation);
        }
        List<Long> ids = selected.stream().map(slot -> slot.player().getId()).toList();
        Map<Long, Human> humans = new HashMap<>();
        humanRepo.findAllById(ids).forEach(human -> humans.put(human.getId(), human));
        Map<Long, PlayerSkills> skills = new HashMap<>();
        playerSkillsRepository.findAllByPlayerIdIn(ids).forEach(row -> skills.put(row.getPlayerId(), row));

        Map<Long, FormationData> savedData = savedFormationData(teamId, formation);
        Map<PlayerPosition, Integer> occurrences = new java.util.EnumMap<>(PlayerPosition.class);
        List<RuntimeLineupSlot> slots = new ArrayList<>(11);
        for (TacticController.StarterSlot selectedSlot : selected) {
            long playerId = selectedSlot.player().getId();
            Human human = humans.get(playerId);
            PlayerSkills playerSkills = skills.get(playerId);
            if (human == null || playerSkills == null) {
                throw new IllegalArgumentException("Missing canonical player data for " + playerId);
            }
            PlayerPosition position = PlayerPosition.require(selectedSlot.usedPosition());
            int occurrence = occurrences.merge(position, 1, Integer::sum);
            slots.add(new RuntimeLineupSlot(human, playerSkills, savedData.get(playerId), position, occurrence));
        }
        return canonicalRuntimeInputFactory.build(tactic, slots);
    }

    private Map<Long, FormationData> savedFormationData(long teamId, String formation) {
        PersonalizedTacticView saved = tacticController.getFormation(String.valueOf(teamId));
        if (saved == null || saved.getTactic() == null || !saved.getTactic().equalsIgnoreCase(formation)
                || saved.getFormationDataList() == null) return Map.of();
        Map<Long, FormationData> result = new HashMap<>();
        saved.getFormationDataList().stream()
                .filter(row -> row != null && row.getPlayerId() > 0 && row.getPositionIndex() < 30)
                .forEach(row -> result.put(row.getPlayerId(), row));
        return result;
    }

    /** Double round-robin among {@code teamIds}: each team uses its manager's formation + chosen
     *  tactic; award 3/1/0 over {@code seasons}; return standings sorted by points desc. */
    public CompetitionResult simulateCompetition(List<Long> teamIds, int seasons) {
        if (teamIds == null || teamIds.size() < 2)
            throw new IllegalArgumentException("simulateCompetition needs at least 2 teams");
        int n = teamIds.size();
        int nSeasons = Math.max(1, Math.min(MAX_SEASONS, seasons <= 0 ? 10 : seasons));

        int[] played = new int[n], wins = new int[n], draws = new int[n], losses = new int[n];
        int[] gf = new int[n], ga = new int[n], pts = new int[n];

        int fixtureOrdinal = 0;
        for (int s = 0; s < nSeasons; s++) {
            for (int h = 0; h < n; h++) {
                for (int a = 0; a < n; a++) {
                    if (h == a) continue; // each ordered pair once => full double round-robin
                    CanonicalStandaloneScore score = scoreCanonicalMatch(
                            teamIds.get(h), teamIds.get(a),
                            "TACTIC_SIM:" + s + ":" + fixtureOrdinal,
                            0L, s + 1, ++fixtureOrdinal);
                    int gH = score.homeGoals(), gA = score.awayGoals();
                    played[h]++; played[a]++;
                    gf[h] += gH; ga[h] += gA;
                    gf[a] += gA; ga[a] += gH;
                    if (gH > gA) { wins[h]++; losses[a]++; pts[h] += 3; }
                    else if (gH < gA) { wins[a]++; losses[h]++; pts[a] += 3; }
                    else { draws[h]++; draws[a]++; pts[h] += 1; pts[a] += 1; }
                }
            }
        }

        List<StandingRow> standings = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = teamIds.get(i);
            String name = teamRepo.findNameById(id);
            standings.add(new StandingRow(id, name == null ? "Team#" + id : name,
                    played[i], wins[i], draws[i], losses[i], gf[i], ga[i], pts[i]));
        }
        standings.sort(Comparator.comparingInt(StandingRow::points)
                .thenComparingInt(r -> r.goalsFor() - r.goalsAgainst())
                .reversed());
        return new CompetitionResult(standings);
    }

    /** League team ids for a team (excluding itself) when no explicit opponents are given. */
    private List<Long> resolveOpponents(long teamId, int season, List<Long> opponentIds) {
        if (opponentIds != null && !opponentIds.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Long id : opponentIds) if (id != null && id != teamId) ids.add(id);
            return ids;
        }
        long compId = findLeagueForTeam(teamId, season);
        List<Long> ids = distinctSortedTeamIds(compId, season);
        ids.remove(teamId);
        return ids;
    }

    private String formationFor(long teamId) {
        return humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .map(Human::getTacticStyle)
                .filter(f -> f != null && !f.isBlank())
                .findFirst()
                .orElse(DEFAULT_FORMATION);
    }

    private String advisorFormationFor(long teamId) {
        return personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId)
                .map(PersonalizedTactic::getTactic)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> formationFor(teamId));
    }

    private long findLeagueForTeam(long teamId, int season) {
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList())
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
                if (cti.getTeamId() == teamId) return compId;
        throw new IllegalArgumentException("Team " + teamId + " not in any top-tier league for season " + season);
    }

    private List<Long> distinctSortedTeamIds(long compId, int season) {
        TreeSet<Long> ids = new TreeSet<>();
        for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
            if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
        return new ArrayList<>(ids);
    }
}
