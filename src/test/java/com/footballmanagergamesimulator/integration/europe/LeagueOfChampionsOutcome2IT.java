package com.footballmanagergamesimulator.integration.europe;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.EuropeanFormatPlan;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.EuropeanCoefficientService;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
import com.footballmanagergamesimulator.service.tournament.TournamentEngine;
import com.footballmanagergamesimulator.testutil.BracketUtil;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.OutcomeTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * League of Champions outcome simulator backed exclusively by the production Compartment V1
 * evaluation, probability formula, score sampler and scoring weights.
 *
 * <p>The original {@link LeagueOfChampionsOutcomeIT} intentionally remains the legacy-engine
 * comparison. This variant shares only the production tournament format and structural group
 * draw. Every preliminary, group and knockout score is sampled from a canonical matchup. Extra
 * time scales the same canonical xG distributions; penalties use the configured weaker-team
 * probability.</p>
 *
 * <h2>Run</h2>
 * <pre>
 * mvn verify -Ptune \
 *   -Dit.test=LeagueOfChampionsOutcome2IT#simulateCanonicalLeagueOfChampionsAndReport \
 *   -Dteam.ids=1,5,8,12,25,50,80,100,2,6,9,13,26,51,81,101
 * </pre>
 *
 * <p>Optional controls:</p>
 * <pre>
 * -Dleg.format=two-leg
 * -Dloc.outcome2.editions=1000
 * -Dloc.outcome2.team-id=14 -Dloc.outcome2.mentality=5 -Dloc.outcome2.tempo=5
 * -Dmatch.engine.compartment.work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier=1.15
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bootstrap.seed=20260528"
})
@DisplayName("League of Champions outcome 2 — production Compartment V1")
class LeagueOfChampionsOutcome2IT {

    private static final int DEFAULT_EDITIONS = 1000;
    private static final long BASE_SEED = 20260528L;
    private static final int COMPETITION_ID = 4;
    private static final String TEAM_IDS_PROPERTY = "team.ids";
    private static final String LEG_FORMAT_PROPERTY = "leg.format";
    private static final String EDITIONS_PROPERTY = "loc.outcome2.editions";
    private static final String OVERRIDE_TEAM_ID_PROPERTY = "loc.outcome2.team-id";
    private static final String MENTALITY_LEVEL_PROPERTY = "loc.outcome2.mentality";
    private static final String TEMPO_LEVEL_PROPERTY = "loc.outcome2.tempo";
    private static final String ATTRIBUTE_TEAM_ID_PROPERTY = "loc.outcome2.attribute-team-id";
    private static final String ATTRIBUTE_PROPERTY = "loc.outcome2.attribute";
    private static final String ATTRIBUTE_DELTA_PROPERTY = "loc.outcome2.attribute-delta";
    private static final String REFUSES_ATTACK_MULTIPLIER_PROPERTY =
            "match.engine.compartment.work-rate.traits.REFUSES_DEFENSIVE_WORK.attack-multiplier";
    private static final List<String> MENTALITY_LEVELS =
            List.of("Very Defensive", "Defensive", "Balanced", "Attacking", "Very Attacking");
    private static final List<String> TEMPO_LEVELS =
            List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private EuropeanCoefficientService coefficientService;
    @Autowired private GameStateService gameStateService;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private CanonicalScoreSampler scoreSampler;
    @Autowired private CanonicalScoringFingerprintService fingerprintService;
    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private TournamentEngine tournamentEngine;

    private int groupSlots;
    private int groupCount;
    private int groupSize;
    private int qualifyPerGroup;
    private int knockoutBracket;
    private int editions;
    private LegFormat legFormat;

    @Test
    @DisplayName("custom League of Champions uses canonical lineups, weights and score PMFs")
    void simulateCanonicalLeagueOfChampionsAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        configureRun();
        CompetitionFormat format = competitionFormat.get(COMPETITION_ID);
        groupCount = format.groupCount();
        groupSize = format.groupSize();
        qualifyPerGroup = format.qualifyPerGroupToKnockout();
        if (qualifyPerGroup != 2) {
            throw new IllegalStateException("League of Champions knockout draw requires exactly "
                    + "two qualifiers per group (winner and runner-up); configured: "
                    + qualifyPerGroup);
        }
        groupSlots = groupCount * groupSize;
        knockoutBracket = groupCount * qualifyPerGroup;

        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(idsProperty).stream().distinct().toList();
        if (teamIds.size() < groupSlots) {
            throw new IllegalArgumentException("Need at least " + groupSlots
                    + " distinct teams for the group stage; got " + teamIds.size());
        }
        EuropeanFormatPlan.derive(teamIds.size(), groupCount, groupSize, qualifyPerGroup);
        TacticOverride override = requestedTacticOverride();
        if (override != null && !teamIds.contains(override.teamId())) {
            throw new IllegalArgumentException("configured tactic override team must belong to -Dteam.ids: "
                    + override.teamId());
        }
        AttributeOverride attributeOverride = requestedAttributeOverride();
        if (attributeOverride != null && !teamIds.contains(attributeOverride.teamId())) {
            throw new IllegalArgumentException("configured attribute override team must belong to -Dteam.ids: "
                    + attributeOverride.teamId());
        }

        List<TeamSetup> teams = loadTeams(teamIds);
        CanonicalTournamentScorer scorer = new CanonicalTournamentScorer(teams);
        StringBuilder firstEditionLog = new StringBuilder();
        Aggregate aggregate = simulateEditions(teams, scorer, firstEditionLog);
        String report = buildReport(teams, aggregate, firstEditionLog.toString(), scorer);
        Path output = Path.of("target", "loc-outcome-2-custom-" + teams.size() + "teams.md");
        Files.writeString(output, report);

        System.out.println();
        System.out.println(report);
        System.out.println("Report written to: " + output.toAbsolutePath());
    }

    private void configureRun() {
        legFormat = BracketUtil.parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));
        String rawEditions = trimmedSystemProperty(EDITIONS_PROPERTY);
        editions = rawEditions == null ? DEFAULT_EDITIONS : positiveInteger(EDITIONS_PROPERTY, rawEditions);

        String rawMultiplier = trimmedSystemProperty(REFUSES_ATTACK_MULTIPLIER_PROPERTY);
        if (rawMultiplier == null) return;
        double multiplier;
        try {
            multiplier = Double.parseDouble(rawMultiplier);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + REFUSES_ATTACK_MULTIPLIER_PROPERTY
                    + " must be numeric; got " + rawMultiplier, exception);
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            throw new IllegalArgumentException("-D" + REFUSES_ATTACK_MULTIPLIER_PROPERTY
                    + " must be finite and > 0; got " + rawMultiplier);
        }
        var rule = compartmentConfig.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (rule == null) throw new IllegalStateException("REFUSES_DEFENSIVE_WORK rule is missing");
        rule.setAttackMultiplier(multiplier);
    }

    private List<TeamSetup> loadTeams(List<Long> teamIds) {
        List<TeamSetup> teams = new ArrayList<>();
        int coefficientSeason = gameStateService.currentSeason() - 1;
        AttributeOverride attributeOverride = requestedAttributeOverride();
        CanonicalMatchEvaluationAdapter adapter =
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
        for (long teamId : teamIds) {
            Team team = teamRepository.findById(teamId)
                    .orElseThrow(() -> new IllegalArgumentException("team does not exist: " + teamId));
            PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(teamId)
                    .orElseGet(LeagueOfChampionsOutcome2IT::defaultTactic);
            tactic = tacticForTeam(teamId, tactic);
            TacticSimulationService.CanonicalFormationEvaluation best =
                    tacticSimulationService.bestCanonicalFormation(teamId, tactic);
            if (attributeOverride != null && attributeOverride.teamId() == teamId) {
                CanonicalRuntimeTeamInput adjustedInput = withAttributeDelta(
                        best.input(), attributeOverride.attribute(), attributeOverride.delta());
                best = new TacticSimulationService.CanonicalFormationEvaluation(
                        best.formation(), adjustedInput, adapter.evaluateTeam(adjustedInput),
                        best.topXiRating());
            }
            double coefficient = coefficientService.getClubCoefficientRolling(teamId, coefficientSeason);
            teams.add(new TeamSetup(teamId, team.getName(), coefficient, team.getReputation(),
                    best.topXiRating(), best.formation(), tactic, best.evaluation(), lineupDetails(best)));
        }
        assertUniquePlayers(teams);
        return List.copyOf(teams);
    }

    private Aggregate simulateEditions(List<TeamSetup> teams, CanonicalTournamentScorer scorer,
                                       StringBuilder firstEditionLog) {
        int teamCount = teams.size();
        int[] stageSizes = BracketUtil.stageSizes(knockoutBracket);
        int[] titles = new int[teamCount];
        long[] reachedGroup = new long[teamCount];
        long[] qualified = new long[teamCount];
        long[] groupPointsTotal = new long[teamCount];
        long[] groupPositionTotal = new long[teamCount];
        long[] knockoutTiesWon = new long[teamCount];
        long[][] reachedAtLeast = new long[teamCount][stageSizes.length];
        Random drawRandom = new Random(BASE_SEED + 1);
        long startedAt = System.nanoTime();

        for (int edition = 0; edition < editions; edition++) {
            EditionOutcome outcome = simulateEdition(edition, teams, scorer, drawRandom,
                    edition == 0 ? firstEditionLog : null);
            titles[outcome.champion()]++;
            for (int team = 0; team < teamCount; team++) {
                if (outcome.reachedGroup()[team]) {
                    reachedGroup[team]++;
                    groupPointsTotal[team] += outcome.groupPoints()[team];
                    groupPositionTotal[team] += outcome.groupPosition()[team];
                }
                if (outcome.qualified()[team]) qualified[team]++;
                knockoutTiesWon[team] += outcome.knockoutTiesWon()[team];
                for (int stage = 0; stage < stageSizes.length; stage++) {
                    if (outcome.knockoutStageReached()[team] <= stageSizes[stage]) {
                        reachedAtLeast[team][stage]++;
                    }
                }
            }
        }
        return new Aggregate(stageSizes, titles, reachedGroup, qualified, groupPointsTotal,
                groupPositionTotal, knockoutTiesWon, reachedAtLeast,
                (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private EditionOutcome simulateEdition(int edition, List<TeamSetup> teams,
                                           CanonicalTournamentScorer scorer, Random drawRandom,
                                           StringBuilder log) {
        int teamCount = teams.size();
        boolean[] reachedGroup = new boolean[teamCount];
        int[] groupPoints = new int[teamCount];
        int[] groupPosition = new int[teamCount];
        boolean[] qualified = new boolean[teamCount];
        int[] knockoutTiesWon = new int[teamCount];
        int[] knockoutStageReached = new int[teamCount];
        Arrays.fill(knockoutStageReached, Integer.MAX_VALUE);
        EditionContext context = new EditionContext(edition);

        List<Integer> current = new ArrayList<>();
        for (int team = 0; team < teamCount; team++) current.add(team);
        int preliminaryRound = 1;
        while (current.size() > groupSlots) {
            int fieldSize = current.size();
            int eliminate = Math.min(fieldSize - groupSlots, fieldSize / 2);
            current.sort(Comparator.comparingDouble((Integer team) -> scorer.strength(team)).reversed());
            List<Integer> byes = new ArrayList<>(current.subList(0, fieldSize - 2 * eliminate));
            List<Integer> playing = new ArrayList<>(current.subList(fieldSize - 2 * eliminate, fieldSize));
            Collections.shuffle(playing, drawRandom);
            List<Integer> survivors = new ArrayList<>(byes);
            if (log != null) {
                log.append("### Preliminary Round ").append(preliminaryRound)
                        .append(" — ").append(eliminate).append(" tie(s), ")
                        .append(byes.size()).append(" bye(s)\n\n");
            }
            for (int index = 0; index < playing.size(); index += 2) {
                int first = playing.get(index);
                int second = playing.get(index + 1);
                CanonicalTieResult tie = scorer.playTie(first, second, legFormat, context,
                        "PRELIM_" + preliminaryRound);
                survivors.add(tie.winner());
                if (log != null) log.append(formatTie(teams, tie)).append('\n');
            }
            if (log != null && !byes.isEmpty()) {
                log.append("- _Byes:_ ").append(namesOf(teams, byes)).append("\n\n");
            }
            current = survivors;
            preliminaryRound++;
        }

        current.forEach(team -> reachedGroup[team] = true);
        current.sort(Comparator
                .comparingDouble((Integer team) -> teams.get(team).coefficient()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Integer team) -> teams.get(team).reputation()).reversed())
                .thenComparingLong(team -> teams.get(team).id()));
        List<List<Integer>> groups = tournamentEngine.potSeededGroups(
                current, groupCount, groupSize, drawRandom, null);
        assertOneTeamPerPot(groups, current);
        if (log != null) {
            log.append("## Group Stage Coefficient Ranking and Pots\n\n");
            for (int seed = 0; seed < current.size(); seed++) {
                TeamSetup team = teams.get(current.get(seed));
                log.append(seed + 1).append(". ").append(team.name())
                        .append(" — coefficient ").append(String.format("%.3f", team.coefficient()))
                        .append(", pot ").append(seed / groupCount + 1).append('\n');
            }
            log.append("\n## Group Stage Draw\n\n");
            for (int group = 0; group < groups.size(); group++) {
                log.append("- **Group ").append((char) ('A' + group)).append("**: ")
                        .append(namesOf(teams, groups.get(group))).append('\n');
            }
            log.append('\n');
        }

        List<GroupResult> groupResults = playGroups(groups, teams, scorer, context, log);
        List<Integer> groupWinners = new ArrayList<>(groupCount);
        List<Integer> groupRunnersUp = new ArrayList<>(groupCount);
        for (GroupResult result : groupResults) {
            if (log != null) {
                log.append("**Group ").append((char) ('A' + result.groupIndex()))
                        .append(" final standings:**\n\n");
            }
            for (int position = 0; position < result.order().size(); position++) {
                int local = result.order().get(position);
                int team = result.teams().get(local);
                groupPoints[team] = result.points()[local];
                groupPosition[team] = position + 1;
                boolean advances = position < qualifyPerGroup;
                if (advances) {
                    qualified[team] = true;
                    if (position == 0) {
                        groupWinners.add(team);
                    } else {
                        groupRunnersUp.add(team);
                    }
                }
                if (log != null) {
                    log.append("  ").append(position + 1).append(". ").append(teams.get(team).name())
                            .append(" — ").append(result.points()[local]).append(" pts (")
                            .append(result.goalsFor()[local]).append('-').append(result.goalsAgainst()[local])
                            .append(')').append(advances ? "  ✅ qualifies" : "").append('\n');
                }
            }
            if (log != null) log.append('\n');
        }

        List<Integer> alive = seededWinnerRunnerUpDraw(groupWinners, groupRunnersUp, drawRandom);
        if (log != null) {
            log.append("### Quarter-final draw — group winners vs group runners-up\n\n");
            for (int index = 0; index < alive.size(); index += 2) {
                log.append("- ").append(teams.get(alive.get(index)).name())
                        .append(" (group winner) vs ")
                        .append(teams.get(alive.get(index + 1)).name())
                        .append(" (group runner-up)\n");
            }
            log.append('\n');
        }
        for (int team : alive) knockoutStageReached[team] = alive.size();
        while (alive.size() > 1) {
            int bracketSize = alive.size();
            if (log != null) log.append("### ").append(BracketUtil.stageLabel(bracketSize)).append("\n\n");
            List<Integer> next = new ArrayList<>(bracketSize / 2);
            for (int index = 0; index < bracketSize; index += 2) {
                CanonicalTieResult tie = scorer.playTie(alive.get(index), alive.get(index + 1),
                        legFormat, context, "KO_" + bracketSize);
                next.add(tie.winner());
                knockoutTiesWon[tie.winner()]++;
                knockoutStageReached[tie.winner()] = bracketSize / 2;
                if (log != null) log.append(formatTie(teams, tie)).append('\n');
            }
            if (log != null) log.append('\n');
            alive = next;
        }
        int champion = alive.get(0);
        if (log != null) log.append("## 🏆 Champion: ").append(teams.get(champion).name()).append("\n\n");
        return new EditionOutcome(champion, reachedGroup, groupPoints, groupPosition, qualified,
                knockoutTiesWon, knockoutStageReached);
    }

    private void assertOneTeamPerPot(List<List<Integer>> groups, List<Integer> coefficientRanking) {
        Map<Integer, Integer> seedRank = new HashMap<>();
        for (int rank = 0; rank < coefficientRanking.size(); rank++) {
            seedRank.put(coefficientRanking.get(rank), rank);
        }
        for (int group = 0; group < groups.size(); group++) {
            Set<Integer> pots = new HashSet<>();
            for (int team : groups.get(group)) {
                Integer rank = seedRank.get(team);
                if (rank == null) {
                    throw new IllegalStateException("group draw contains an unranked team: " + team);
                }
                pots.add(rank / groupCount + 1);
            }
            if (groups.get(group).size() != groupSize || pots.size() != groupSize) {
                throw new IllegalStateException("group " + (group + 1)
                        + " must contain exactly one team from each coefficient pot; pots=" + pots);
            }
        }
    }

    static List<Integer> seededWinnerRunnerUpDraw(List<Integer> groupWinners,
                                                   List<Integer> groupRunnersUp,
                                                   Random drawRandom) {
        if (groupWinners.isEmpty() || groupWinners.size() != groupRunnersUp.size()) {
            throw new IllegalArgumentException("knockout draw requires equally sized, non-empty "
                    + "winner and runner-up pots");
        }
        List<Integer> winnersPot = new ArrayList<>(groupWinners);
        List<Integer> runnersUpPot = new ArrayList<>(groupRunnersUp);
        Collections.shuffle(winnersPot, drawRandom);
        Collections.shuffle(runnersUpPot, drawRandom);

        List<Integer> draw = new ArrayList<>(winnersPot.size() * 2);
        for (int index = 0; index < winnersPot.size(); index++) {
            draw.add(winnersPot.get(index));
            draw.add(runnersUpPot.get(index));
        }
        return draw;
    }

    private List<GroupResult> playGroups(List<List<Integer>> groups, List<TeamSetup> teams,
                                         CanonicalTournamentScorer scorer, EditionContext context,
                                         StringBuilder log) {
        int[][] points = new int[groupCount][groupSize];
        int[][] goalsFor = new int[groupCount][groupSize];
        int[][] goalsAgainst = new int[groupCount][groupSize];

        for (int matchday = 0; matchday < BracketUtil.GROUP_SCHEDULE.length; matchday++) {
            if (log != null) log.append("### Group Stage — Matchday ").append(matchday + 1).append("\n\n");
            int[][] fixtures = BracketUtil.GROUP_SCHEDULE[matchday];
            for (int group = 0; group < groups.size(); group++) {
                List<Integer> members = groups.get(group);
                for (int[] fixture : fixtures) {
                    int homeLocal = fixture[0];
                    int awayLocal = fixture[1];
                    int home = members.get(homeLocal);
                    int away = members.get(awayLocal);
                    Score score = scorer.score(home, away, context,
                            "GROUP_" + group + "_MD_" + (matchday + 1));
                    goalsFor[group][homeLocal] += score.home();
                    goalsAgainst[group][homeLocal] += score.away();
                    goalsFor[group][awayLocal] += score.away();
                    goalsAgainst[group][awayLocal] += score.home();
                    if (score.home() > score.away()) points[group][homeLocal] += 3;
                    else if (score.home() < score.away()) points[group][awayLocal] += 3;
                    else {
                        points[group][homeLocal]++;
                        points[group][awayLocal]++;
                    }
                    if (log != null) {
                        log.append("- [Group ").append((char) ('A' + group)).append("] ")
                                .append(teams.get(home).name()).append(' ').append(score.home())
                                .append('–').append(score.away()).append(' ')
                                .append(teams.get(away).name()).append('\n');
                    }
                }
            }
            if (log != null) log.append('\n');
        }

        List<GroupResult> results = new ArrayList<>(groupCount);
        for (int group = 0; group < groupCount; group++) {
            List<Integer> members = groups.get(group);
            List<Integer> order = new ArrayList<>();
            for (int local = 0; local < groupSize; local++) order.add(local);
            int groupIndex = group;
            order.sort((first, second) -> {
                if (points[groupIndex][first] != points[groupIndex][second]) {
                    return Integer.compare(points[groupIndex][second], points[groupIndex][first]);
                }
                int firstDifference = goalsFor[groupIndex][first] - goalsAgainst[groupIndex][first];
                int secondDifference = goalsFor[groupIndex][second] - goalsAgainst[groupIndex][second];
                if (firstDifference != secondDifference) return Integer.compare(secondDifference, firstDifference);
                if (goalsFor[groupIndex][first] != goalsFor[groupIndex][second]) {
                    return Integer.compare(goalsFor[groupIndex][second], goalsFor[groupIndex][first]);
                }
                return Double.compare(scorer.strength(members.get(second)), scorer.strength(members.get(first)));
            });
            results.add(new GroupResult(group, List.copyOf(members), points[group], goalsFor[group],
                    goalsAgainst[group], List.copyOf(order)));
        }
        return results;
    }

    private String buildReport(List<TeamSetup> teams, Aggregate aggregate, String firstEditionLog,
                               CanonicalTournamentScorer scorer) {
        int finalIndex = indexOf(aggregate.stageSizes(), 2);
        int semiIndex = indexOf(aggregate.stageSizes(), 4);
        int quarterIndex = indexOf(aggregate.stageSizes(), 8);
        Integer[] order = new Integer[teams.size()];
        for (int team = 0; team < teams.size(); team++) order[team] = team;
        Arrays.sort(order, (first, second) -> {
            if (aggregate.titles()[first] != aggregate.titles()[second]) {
                return Integer.compare(aggregate.titles()[second], aggregate.titles()[first]);
            }
            return Double.compare(scorer.strength(second), scorer.strength(first));
        });

        StringBuilder report = new StringBuilder("# League of Champions Outcome 2 — Compartment V1\n\n")
                .append("Run on ").append(LocalDateTime.now()).append('\n')
                .append("Engine: COMPARTMENT_V1 + production CanonicalScoreSampler\n")
                .append("Weights: classpath:compartment-scoring-weights-v1.yml\n")
                .append("Config fingerprint: ")
                .append(fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig)).append('\n')
                .append("Teams: ").append(teams.size()).append('\n')
                .append("Editions: ").append(editions).append('\n')
                .append("Format: ").append(groupCount).append(" groups of ").append(groupSize)
                .append(", top ").append(qualifyPerGroup).append("; ")
                .append(legFormat == LegFormat.TWO_LEG ? "two-leg" : "single-leg")
                .append(" knockout\n")
                .append("Extra-time canonical xG scale: ")
                .append(String.format("%.4f", compartmentConfig.getProbability().getExtraTimeScale())).append('\n')
                .append("Matchup exponent: ")
                .append(String.format("%.4f", compartmentConfig.getProbability().getMatchupExponent())).append('\n')
                .append("Penalty weaker-team win chance: ")
                .append(String.format("%.1f%%", matchEngineConfig.getKnockout().getPenaltyWeakerTeamWinChance() * 100))
                .append('\n')
                .append("Tactic override: ").append(tacticOverrideLabel()).append('\n')
                .append("Attribute override: ").append(attributeOverrideLabel()).append('\n')
                .append("REFUSES_DEFENSIVE_WORK attack multiplier: ")
                .append(String.format("%.4f", refusesAttackMultiplier())).append('\n')
                .append("Elapsed: ").append(aggregate.elapsedMs()).append(" ms\n\n")
                .append("## Results After ").append(editions).append(" Editions\n\n");

        MarkdownTable table = new MarkdownTable(
                List.of("Rank", "Team", "Top XI", "GK", "Attack", "Midfield", "Defense",
                        "Final Attack", "Final Protection", "Tactic", "Trophies", "Reach grp",
                        "Qualify", "Avg grp pos", "Avg grp pts", "Final", "Semi", "QF", "KO won"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT));
        for (int rank = 0; rank < order.length; rank++) {
            int team = order[rank];
            TeamSetup setup = teams.get(team);
            CanonicalStrength strength = scorer.canonicalStrength(team);
            long groupRuns = aggregate.reachedGroup()[team];
            table.addRow(String.valueOf(rank + 1), setup.name(), String.format("%.1f", setup.topXiRating()),
                    String.format("%.1f", setup.goalkeeperRating()), String.format("%.2f", strength.rawAttack()),
                    String.format("%.2f", strength.rawMidfield()), String.format("%.2f", strength.rawDefense()),
                    String.format("%.2f", strength.finalAttack()), String.format("%.2f", strength.finalProtection()),
                    tacticLabel(setup), percentage(aggregate.titles()[team]),
                    percentage(aggregate.reachedGroup()[team]), percentage(aggregate.qualified()[team]),
                    groupRuns == 0 ? "—" : String.format("%.2f", aggregate.groupPositionTotal()[team] / (double) groupRuns),
                    groupRuns == 0 ? "—" : String.format("%.1f", aggregate.groupPointsTotal()[team] / (double) groupRuns),
                    stagePercentage(aggregate, team, finalIndex), stagePercentage(aggregate, team, semiIndex),
                    stagePercentage(aggregate, team, quarterIndex),
                    String.format("%.2f", aggregate.knockoutTiesWon()[team] / (double) editions));
        }
        report.append(table.render()).append('\n')
                .append("## Knockout Stage Reach\n\n");

        List<String> stageHeaders = new ArrayList<>();
        List<MarkdownTable.Align> stageAlignments = new ArrayList<>();
        stageHeaders.add("Team");
        stageAlignments.add(MarkdownTable.Align.LEFT);
        for (int stageSize : aggregate.stageSizes()) {
            stageHeaders.add(BracketUtil.stageLabel(stageSize));
            stageAlignments.add(MarkdownTable.Align.RIGHT);
        }
        stageHeaders.add("Winner");
        stageAlignments.add(MarkdownTable.Align.RIGHT);
        MarkdownTable stages = new MarkdownTable(stageHeaders, stageAlignments);
        for (int team : order) {
            List<String> row = new ArrayList<>();
            row.add(teams.get(team).name());
            for (int stage = 0; stage < aggregate.stageSizes().length; stage++) {
                row.add(percentage(aggregate.reachedAtLeast()[team][stage]));
            }
            row.add(percentage(aggregate.titles()[team]));
            stages.addRow(row.toArray(String[]::new));
        }
        report.append(stages.render()).append('\n')
                .append("# First Edition — Phase by Phase\n\n")
                .append(firstEditionLog)
                .append("## Starting XIs — Top 3\n\n")
                .append("Listed in formation order. Rating is canonical Attack + Midfield + Defense for the used slot.\n\n");
        for (int rank = 0; rank < Math.min(3, order.length); rank++) {
            TeamSetup team = teams.get(order[rank]);
            report.append(rank + 1).append(". **").append(team.name()).append(" — ")
                    .append(team.formation()).append("**: ")
                    .append(team.lineup().stream().map(LeagueOfChampionsOutcome2IT::lineupLabel)
                            .collect(Collectors.joining(" - ")))
                    .append("\n\n");
        }
        report.append("## Method\n\n")
                .append("- Formation and XI are selected exclusively by the persisted 1–300 `Human.rating` values: best natural player per slot, then best-rated fillers.\n")
                .append("- Engine weights and tactical axes are applied only after that formation and XI are frozen.\n")
                .append("- All player position, role, foot, attribute, tactic, mentality and Stay Forward effects come from the production Compartment weights.\n")
                .append("- Group and knockout scores use production canonical PMFs and deterministic production seeds.\n")
                .append("- Extra time resamples canonical home/away xG at the configured 30-minute scale.\n")
                .append("- Structural draws are deterministic; no legacy scalar score calculation is used.\n");
        return report.toString();
    }

    private List<LineupPlayer> lineupDetails(TacticSimulationService.CanonicalFormationEvaluation best) {
        List<Long> ids = best.evaluation().players().stream().map(player -> player.playerId()).toList();
        Map<Long, Human> humans = new HashMap<>();
        humanRepository.findAllById(ids).forEach(human -> humans.put(human.getId(), human));
        return best.evaluation().players().stream().map(player -> {
                    Human human = humans.get(player.playerId());
                    if (human == null) throw new IllegalStateException("missing selected player " + player.playerId());
                    double rating = player.rating().compartments().values().stream()
                            .mapToDouble(compartment -> compartment.finalScore()).sum();
                    return new LineupPlayer(player.playerId(), human.getName(), player.usedPosition(),
                            player.occurrence(), player.role() == null ? null : player.role().displayName(),
                            rating, human.isStayForward());
                }).sorted(Comparator.comparingInt(LeagueOfChampionsOutcome2IT::lineupOrder)
                        .thenComparingInt(LineupPlayer::occurrence).thenComparingLong(LineupPlayer::playerId))
                .toList();
    }

    private PersonalizedTactic tacticForTeam(long teamId, PersonalizedTactic source) {
        TacticOverride override = requestedTacticOverride();
        if (override == null || override.teamId() != teamId) return source;
        PersonalizedTactic copy = copyTactic(source);
        if (override.mentality() != null) copy.setMentality(override.mentality());
        if (override.tempo() != null) copy.setTempo(override.tempo());
        return copy;
    }

    private static PersonalizedTactic copyTactic(PersonalizedTactic source) {
        PersonalizedTactic copy = defaultTactic();
        copy.setMentality(tacticValue(source.getMentality(), "Balanced"));
        copy.setTempo(tacticValue(source.getTempo(), "Standard"));
        copy.setPassingType(tacticValue(source.getPassingType(), "Normal"));
        copy.setDefensiveLine(tacticValue(source.getDefensiveLine(), "Standard"));
        copy.setPressing(tacticValue(source.getPressing(), "Standard"));
        copy.setWidth(tacticValue(source.getWidth(), "Balanced"));
        return copy;
    }

    private CanonicalRuntimeTeamInput withAttributeDelta(CanonicalRuntimeTeamInput input,
                                                          PlayerAttribute attribute,
                                                          int delta) {
        int minimum = compartmentConfig.getRating().getAttributeMin();
        int maximum = compartmentConfig.getRating().getAttributeMax();
        List<CanonicalLineupPlayer> adjusted = input.lineup().stream().map(player -> {
            Integer current = player.attributes().get(attribute);
            if (current == null) {
                throw new IllegalArgumentException("player " + player.playerId()
                        + " does not expose attribute " + attribute);
            }
            long candidate = (long) current + delta;
            int clamped = (int) Math.max(minimum, Math.min(maximum, candidate));
            EnumMap<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
            attributes.putAll(player.attributes());
            attributes.put(attribute, clamped);
            return new CanonicalLineupPlayer(
                    player.playerId(), player.usedPosition(), player.occurrence(), player.role(),
                    player.duty(), attributes, player.fitness(), player.morale(), player.capability(),
                    player.roleSuitability(), player.traits(), player.forwardInstruction());
        }).toList();
        return new CanonicalRuntimeTeamInput(input.mentality(), adjusted, input.tacticalContexts());
    }

    private static PersonalizedTactic defaultTactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Balanced");
        tactic.setTempo("Standard");
        tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard");
        tactic.setPressing("Standard");
        tactic.setWidth("Balanced");
        return tactic;
    }

    private static TacticOverride requestedTacticOverride() {
        String rawTeamId = trimmedSystemProperty(OVERRIDE_TEAM_ID_PROPERTY);
        String rawMentality = trimmedSystemProperty(MENTALITY_LEVEL_PROPERTY);
        String rawTempo = trimmedSystemProperty(TEMPO_LEVEL_PROPERTY);
        if (rawTeamId == null && rawMentality == null && rawTempo == null) return null;
        if (rawTeamId == null) {
            throw new IllegalArgumentException("-D" + OVERRIDE_TEAM_ID_PROPERTY
                    + " is required with a mentality or tempo override");
        }
        long teamId;
        try {
            teamId = Long.parseLong(rawTeamId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + OVERRIDE_TEAM_ID_PROPERTY
                    + " must be a positive integer; got " + rawTeamId, exception);
        }
        if (teamId <= 0) throw new IllegalArgumentException("override team id must be positive");
        if (rawMentality == null && rawTempo == null) {
            throw new IllegalArgumentException("supply at least mentality or tempo for the override team");
        }
        return new TacticOverride(teamId,
                levelValue(MENTALITY_LEVEL_PROPERTY, rawMentality, MENTALITY_LEVELS),
                levelValue(TEMPO_LEVEL_PROPERTY, rawTempo, TEMPO_LEVELS));
    }

    private static AttributeOverride requestedAttributeOverride() {
        String rawTeamId = trimmedSystemProperty(ATTRIBUTE_TEAM_ID_PROPERTY);
        String rawAttribute = trimmedSystemProperty(ATTRIBUTE_PROPERTY);
        String rawDelta = trimmedSystemProperty(ATTRIBUTE_DELTA_PROPERTY);
        if (rawTeamId == null && rawAttribute == null && rawDelta == null) return null;
        if (rawTeamId == null || rawAttribute == null || rawDelta == null) {
            throw new IllegalArgumentException("attribute override requires -D"
                    + ATTRIBUTE_TEAM_ID_PROPERTY + ", -D" + ATTRIBUTE_PROPERTY
                    + " and -D" + ATTRIBUTE_DELTA_PROPERTY);
        }
        long teamId;
        int delta;
        try {
            teamId = Long.parseLong(rawTeamId);
            delta = Integer.parseInt(rawDelta);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("attribute override team id and delta must be integers", exception);
        }
        if (teamId <= 0) throw new IllegalArgumentException("attribute override team id must be positive");
        if (delta == 0) throw new IllegalArgumentException("attribute override delta must be non-zero");
        String normalized = rawAttribute.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        PlayerAttribute attribute;
        try {
            attribute = PlayerAttribute.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown canonical player attribute: " + rawAttribute, exception);
        }
        return new AttributeOverride(teamId, attribute, delta);
    }

    private static String levelValue(String property, String raw, List<String> values) {
        if (raw == null) return null;
        int level = positiveInteger(property, raw);
        if (level > values.size()) {
            throw new IllegalArgumentException("-D" + property + " must be in [1,5]; got " + raw);
        }
        return values.get(level - 1);
    }

    private static int positiveInteger(String property, String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // normalized below
        }
        throw new IllegalArgumentException("-D" + property + " must be a positive integer; got " + raw);
    }

    private static String trimmedSystemProperty(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String tacticOverrideLabel() {
        TacticOverride override = requestedTacticOverride();
        if (override == null) return "none";
        return "teamId=" + override.teamId()
                + ", mentality=" + (override.mentality() == null ? "persisted" : override.mentality())
                + ", tempo=" + (override.tempo() == null ? "persisted" : override.tempo());
    }

    private String attributeOverrideLabel() {
        AttributeOverride override = requestedAttributeOverride();
        if (override == null) return "none";
        return "teamId=" + override.teamId() + ", attribute=" + override.attribute()
                + ", delta=" + (override.delta() > 0 ? "+" : "") + override.delta()
                + ", clamped=[" + compartmentConfig.getRating().getAttributeMin() + ','
                + compartmentConfig.getRating().getAttributeMax() + ']';
    }

    private double refusesAttackMultiplier() {
        var rule = compartmentConfig.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (rule == null) throw new IllegalStateException("REFUSES_DEFENSIVE_WORK rule is missing");
        return rule.getAttackMultiplier();
    }

    private static void assertUniquePlayers(List<TeamSetup> teams) {
        Set<Long> ids = new HashSet<>();
        for (TeamSetup team : teams) {
            for (var player : team.evaluation().players()) {
                if (!ids.add(player.playerId())) {
                    throw new IllegalArgumentException("player belongs to multiple simulated teams: "
                            + player.playerId());
                }
            }
        }
    }

    private static String formatTie(List<TeamSetup> teams, CanonicalTieResult tie) {
        return "- " + teams.get(tie.first()).name() + " vs " + teams.get(tie.second()).name()
                + " — " + tie.summary() + "  → **" + teams.get(tie.winner()).name() + "** advances";
    }

    private static String namesOf(List<TeamSetup> teams, List<Integer> indices) {
        return indices.stream().map(index -> teams.get(index).name()).collect(Collectors.joining(", "));
    }

    private String percentage(long count) {
        return String.format("%.1f%%", count * 100.0 / editions);
    }

    private String stagePercentage(Aggregate aggregate, int team, int stageIndex) {
        return stageIndex < 0 ? "—" : percentage(aggregate.reachedAtLeast()[team][stageIndex]);
    }

    private static int indexOf(int[] values, int target) {
        for (int index = 0; index < values.length; index++) if (values[index] == target) return index;
        return -1;
    }

    private static String tacticLabel(TeamSetup team) {
        PersonalizedTactic tactic = team.tactic();
        return team.formation() + " — " + String.join(" / ",
                tacticValue(tactic.getMentality(), "Balanced"), tacticValue(tactic.getTempo(), "Standard"),
                tacticValue(tactic.getPassingType(), "Normal"),
                tacticValue(tactic.getDefensiveLine(), "Standard"),
                tacticValue(tactic.getPressing(), "Standard"), tacticValue(tactic.getWidth(), "Balanced"));
    }

    private static String tacticValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int lineupOrder(LineupPlayer player) {
        return switch (player.position()) {
            case GK -> 0;
            case DL, WBL -> 10;
            case DC -> 20;
            case DR, WBR -> 30;
            case DM -> 40;
            case ML -> 50;
            case MC -> 60;
            case MR -> 70;
            case AML -> 80;
            case AMC -> 90;
            case AMR -> 100;
            case ST -> 110;
        };
    }

    private static String lineupLabel(LineupPlayer player) {
        String slot = player.position().code() + (player.occurrence() > 1 ? player.occurrence() : "");
        StringBuilder details = new StringBuilder(String.format("%.1f", player.rating()));
        if (player.role() != null) details.append(", ").append(player.role());
        if (player.stayForward()) details.append(", Stay Forward");
        return player.name() + " — " + slot + " (" + details + ")";
    }

    private final class CanonicalTournamentScorer {
        private final List<TeamSetup> teams;
        private final CanonicalMatchEvaluation[][] evaluations;
        private final CanonicalStrength[] canonicalStrengths;
        private final double[] strengths;
        private final GoalProbabilityFormula probabilityFormula;

        private CanonicalTournamentScorer(List<TeamSetup> teams) {
            this.teams = teams;
            int count = teams.size();
            evaluations = new CanonicalMatchEvaluation[count][count];
            canonicalStrengths = new CanonicalStrength[count];
            strengths = new double[count];
            CanonicalMatchEvaluationAdapter adapter =
                    new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
            probabilityFormula = new GoalProbabilityFormula(compartmentConfig);
            for (int team = 0; team < count; team++) {
                var evaluation = teams.get(team).evaluation().team();
                canonicalStrengths[team] = new CanonicalStrength(
                        evaluation.rawTotals().attack(), evaluation.rawTotals().midfield(),
                        evaluation.rawTotals().defense(), evaluation.attack(), evaluation.attackProtection());
                strengths[team] = evaluation.attack() + evaluation.attackProtection();
            }
            for (int home = 0; home < count; home++) {
                for (int away = 0; away < count; away++) {
                    if (home != away) {
                        evaluations[home][away] = adapter.evaluate(
                                teams.get(home).evaluation(), teams.get(away).evaluation(), MatchVenue.HOME);
                    }
                }
            }
        }

        private Score score(int home, int away, EditionContext context, String phase) {
            int ordinal = context.nextOrdinal();
            String fixtureKey = "LOC_V2:" + context.edition() + ':' + phase + ':' + ordinal + ':'
                    + teams.get(home).id() + ':' + teams.get(away).id();
            long seed = MatchPlanService.seedFor(fixtureKey, COMPETITION_ID, context.edition() + 1,
                    ordinal, teams.get(home).id(), teams.get(away).id());
            CanonicalScoreSampler.GoalSample sample = scoreSampler.sample(evaluations[home][away], seed);
            return new Score(sample.homeGoals(), sample.awayGoals());
        }

        private CanonicalTieResult playTie(int first, int second, LegFormat format,
                                           EditionContext context, String phase) {
            Score legOne = score(first, second, context, phase + "_L1");
            int legOneFirst = legOne.home();
            int legOneSecond = legOne.away();
            int legTwoFirst = -1;
            int legTwoSecond = -1;
            int aggregateFirst = legOneFirst;
            int aggregateSecond = legOneSecond;
            CanonicalMatchEvaluation extraTimeEvaluation = evaluations[first][second];
            boolean reverseExtraTime = false;
            if (format == LegFormat.TWO_LEG) {
                Score legTwo = score(second, first, context, phase + "_L2");
                legTwoFirst = legTwo.away();
                legTwoSecond = legTwo.home();
                aggregateFirst += legTwoFirst;
                aggregateSecond += legTwoSecond;
                extraTimeEvaluation = evaluations[second][first];
                reverseExtraTime = true;
            }

            int extraFirst = 0;
            int extraSecond = 0;
            int penaltyFirst = 0;
            int penaltySecond = 0;
            boolean extraTime = false;
            boolean penalties = false;
            int winner;
            if (aggregateFirst != aggregateSecond) {
                winner = aggregateFirst > aggregateSecond ? first : second;
            } else {
                extraTime = true;
                Score extra = scoreExtraTime(extraTimeEvaluation, first, second, context, phase + "_ET");
                extraFirst = reverseExtraTime ? extra.away() : extra.home();
                extraSecond = reverseExtraTime ? extra.home() : extra.away();
                if (aggregateFirst + extraFirst != aggregateSecond + extraSecond) {
                    winner = aggregateFirst + extraFirst > aggregateSecond + extraSecond ? first : second;
                } else {
                    penalties = true;
                    long penaltySeed = tiebreakSeed(first, second, context, phase + "_PEN");
                    SplittableRandom random = new SplittableRandom(penaltySeed);
                    boolean firstIsWeaker = strength(first) < strength(second);
                    double firstWinChance = firstIsWeaker
                            ? matchEngineConfig.getKnockout().getPenaltyWeakerTeamWinChance()
                            : 1.0 - matchEngineConfig.getKnockout().getPenaltyWeakerTeamWinChance();
                    boolean firstWon = random.nextDouble() < firstWinChance;
                    int loserScore = 2 + random.nextInt(4);
                    int winnerScore = loserScore + 1;
                    penaltyFirst = firstWon ? winnerScore : loserScore;
                    penaltySecond = firstWon ? loserScore : winnerScore;
                    winner = firstWon ? first : second;
                }
            }
            return new CanonicalTieResult(format, first, second, winner, legOneFirst, legOneSecond,
                    legTwoFirst, legTwoSecond, aggregateFirst, aggregateSecond, extraTime,
                    extraFirst, extraSecond, penalties, penaltyFirst, penaltySecond);
        }

        private Score scoreExtraTime(CanonicalMatchEvaluation evaluation, int first, int second,
                                     EditionContext context, String phase) {
            int ordinal = context.nextOrdinal();
            String fixtureKey = "LOC_V2:" + context.edition() + ':' + phase + ':' + ordinal + ':'
                    + teams.get(first).id() + ':' + teams.get(second).id();
            long seed = MatchPlanService.seedFor(fixtureKey, COMPETITION_ID, context.edition() + 1,
                    ordinal, teams.get(first).id(), teams.get(second).id());
            SplittableRandom random = new SplittableRandom(seed);
            double scale = compartmentConfig.getProbability().getExtraTimeScale();
            GoalProbabilityFormula.GoalDistribution home = probabilityFormula.predictiveGoals(
                    evaluation.probability().homeXg() * scale);
            GoalProbabilityFormula.GoalDistribution away = probabilityFormula.predictiveGoals(
                    evaluation.probability().awayXg() * scale);
            return new Score(sample(home, random.nextDouble()), sample(away, random.nextDouble()));
        }

        private long tiebreakSeed(int first, int second, EditionContext context, String phase) {
            int ordinal = context.nextOrdinal();
            return MatchPlanService.seedFor("LOC_V2:" + context.edition() + ':' + phase + ':' + ordinal,
                    COMPETITION_ID, context.edition() + 1, ordinal,
                    teams.get(first).id(), teams.get(second).id());
        }

        private int sample(GoalProbabilityFormula.GoalDistribution distribution, double random) {
            double cumulative = 0.0;
            double[] probabilities = distribution.probabilities();
            for (int goal = 0; goal < probabilities.length; goal++) {
                cumulative += probabilities[goal];
                if (random < cumulative) return goal;
            }
            return probabilities.length - 1;
        }

        private double strength(int team) {
            return strengths[team];
        }

        private CanonicalStrength canonicalStrength(int team) {
            return canonicalStrengths[team];
        }
    }

    private record TeamSetup(long id, String name, double coefficient, int reputation,
                             double topXiRating, String formation, PersonalizedTactic tactic,
                             CanonicalTeamEvaluation evaluation, List<LineupPlayer> lineup) {
        private TeamSetup {
            lineup = List.copyOf(lineup);
        }

        private double goalkeeperRating() {
            return evaluation.players().stream()
                    .filter(player -> player.usedPosition() == PlayerPosition.GK)
                    .mapToDouble(player -> player.rating().compartments().values().stream()
                            .mapToDouble(compartment -> compartment.finalScore()).sum())
                    .findFirst().orElseThrow(() -> new IllegalStateException("team has no goalkeeper: " + id));
        }
    }

    private record LineupPlayer(long playerId, String name, PlayerPosition position, int occurrence,
                                String role, double rating, boolean stayForward) {}

    private record TacticOverride(long teamId, String mentality, String tempo) {}

    private record AttributeOverride(long teamId, PlayerAttribute attribute, int delta) {}

    private record CanonicalStrength(double rawAttack, double rawMidfield, double rawDefense,
                                     double finalAttack, double finalProtection) {}

    private record Score(int home, int away) {}

    private static final class EditionContext {
        private final int edition;
        private int ordinal;

        private EditionContext(int edition) {
            this.edition = edition;
        }

        private int edition() {
            return edition;
        }

        private int nextOrdinal() {
            return ++ordinal;
        }
    }

    private record GroupResult(int groupIndex, List<Integer> teams, int[] points,
                               int[] goalsFor, int[] goalsAgainst, List<Integer> order) {}

    private record EditionOutcome(int champion, boolean[] reachedGroup, int[] groupPoints,
                                  int[] groupPosition, boolean[] qualified, int[] knockoutTiesWon,
                                  int[] knockoutStageReached) {}

    private record Aggregate(int[] stageSizes, int[] titles, long[] reachedGroup, long[] qualified,
                             long[] groupPointsTotal, long[] groupPositionTotal, long[] knockoutTiesWon,
                             long[][] reachedAtLeast, long elapsedMs) {}

    private record CanonicalTieResult(LegFormat format, int first, int second, int winner,
                                      int legOneFirst, int legOneSecond,
                                      int legTwoFirst, int legTwoSecond,
                                      int aggregateFirst, int aggregateSecond,
                                      boolean extraTime, int extraFirst, int extraSecond,
                                      boolean penalties, int penaltyFirst, int penaltySecond) {
        private String summary() {
            StringBuilder value = new StringBuilder();
            if (format == LegFormat.TWO_LEG) {
                value.append("leg 1 ").append(legOneFirst).append('-').append(legOneSecond)
                        .append(", leg 2 ").append(legTwoFirst).append('-').append(legTwoSecond)
                        .append(", aggregate ").append(aggregateFirst).append('-').append(aggregateSecond);
            } else {
                value.append(legOneFirst).append('-').append(legOneSecond);
            }
            if (extraTime) value.append(", ET ").append(extraFirst).append('-').append(extraSecond);
            if (penalties) value.append(", pens ").append(penaltyFirst).append('-').append(penaltySecond);
            return value.toString();
        }
    }
}
