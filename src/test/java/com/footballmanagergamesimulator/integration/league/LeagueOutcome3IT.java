package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.OutcomeTestSupport;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast 200-season league outcome report using the production Compartment V1 model.
 *
 * <p>Unlike {@link LeagueOutcome2IT}, this test exploits the fact that its squads and tactics are
 * immutable during the run. It builds each canonical team once, evaluates every directed fixture
 * once, then samples that exact production probability distribution 200 times with independent,
 * deterministic production seeds. Every sample still belongs to its own season table: scores are
 * never averaged before standings are calculated.</p>
 *
 * <p>This is statistically equivalent to repeating the same fixed-input season 200 times. It is
 * intentionally not a career simulator: transfers, injuries, fitness, morale and tactics do not
 * evolve between samples.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.compartment.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("League outcome 3 — fast 200-season Compartment V1 sampling")
class LeagueOutcome3IT {

    private static final int SEASONS = 200;
    private static final long BASE_SEED = 20260528L;
    private static final String TEAM_IDS_PROPERTY = "team.ids";
    private static final List<PlayerPosition> FOUR_FOUR_TWO = List.of(
            PlayerPosition.GK, PlayerPosition.DC, PlayerPosition.DC,
            PlayerPosition.DL, PlayerPosition.DR, PlayerPosition.MC,
            PlayerPosition.MC, PlayerPosition.AML, PlayerPosition.AMR,
            PlayerPosition.ST, PlayerPosition.ST);

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CanonicalRuntimeInputFactory runtimeInputFactory;
    @Autowired private CanonicalScoreSampler scoreSampler;
    @Autowired private CanonicalScoringFingerprintService fingerprintService;
    @Autowired private GameStateService gameState;
    @Autowired private PlayerCapabilityService playerCapabilityService;
    @Autowired private OutcomeTestSupport support;

    @Test
    @DisplayName("all leagues, 200 independent standings, one canonical evaluation per fixture")
    void simulateAllLeaguesWithPreparedCanonicalEvaluations() throws Exception {
        List<Long> availableLeagues = availableLeagues();
        int season = gameState.currentSeason();
        assertThat(compartmentConfig.isEnabled()).isTrue();

        StringBuilder combined = new StringBuilder("# League Outcome 3 — All Leagues\n\n")
                .append("Leagues: ").append(availableLeagues).append('\n')
                .append("Seasons sampled per league: ").append(SEASONS).append("\n\n");
        for (long competitionId : availableLeagues) {
            Competition league = competitionRepository.findById(competitionId)
                    .orElseThrow(() -> new IllegalStateException("league does not exist: " + competitionId));
            List<TeamSetup> teams = loadTeams(competitionId, season);
            assertThat(teams).as("league %s must have teams", competitionId).isNotEmpty();

            AggregatedSimulation aggregate = runAggregateSimulation(competitionId, teams);
            String report = buildReport("Competition: id=" + competitionId + ", name="
                            + league.getName() + ", season=" + season,
                    availableLeagues, teams, aggregate);
            Files.writeString(Path.of("target", "league-outcome-3-" + competitionId + ".md"), report);
            combined.append(report).append("\n\n---\n\n");
            assertCompleteSimulation(teams, aggregate);
        }
        writeAndPrint(Path.of("target", "league-outcome-3-all.md"), combined.toString());
    }

    @Test
    @DisplayName("custom -Dteam.ids league uses the same prepared canonical path")
    void simulateCustomTeamsWithPreparedCanonicalEvaluations() throws Exception {
        String rawIds = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(rawIds != null && !rawIds.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");
        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(rawIds);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 teams; got " + teamIds.size());
        }
        List<TeamSetup> teams = loadTeamsByIds(teamIds);
        long scoringCompetitionId = availableLeagues().get(0);
        AggregatedSimulation aggregate = runAggregateSimulation(scoringCompetitionId, teams);
        String label = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining(", "));
        String filenameIds = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining("_"));
        String report = buildReport("Competition: CUSTOM league of " + teams.size()
                        + " teams (IDs: " + label + ")", null, teams, aggregate);
        writeAndPrint(Path.of("target", "league-outcome-3-custom-" + filenameIds + ".md"), report);
        assertCompleteSimulation(teams, aggregate);
    }

    private List<Long> availableLeagues() {
        List<Long> leagues = gameState.getLeagueCompetitionIdsCached().stream().sorted().toList();
        assertThat(leagues).as("at least one league competition must be bootstrapped").isNotEmpty();
        return leagues;
    }

    private List<TeamSetup> loadTeams(long competitionId, int season) {
        List<Long> teamIds = competitionTeamInfoRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .map(CompetitionTeamInfo::getTeamId)
                .filter(id -> id > 0).distinct().sorted().toList();
        return loadTeamsByIds(teamIds);
    }

    private List<TeamSetup> loadTeamsByIds(List<Long> teamIds) {
        List<TeamSetup> result = new ArrayList<>();
        for (long teamId : teamIds.stream().distinct().sorted().toList()) {
            assertThat(teamRepository.existsById(teamId)).as("team %s must exist", teamId).isTrue();
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                    .stream()
                    .filter(player -> !player.isRetired())
                    .sorted(Comparator.comparingDouble(Human::getRating)
                            .reversed().thenComparingLong(Human::getId))
                    .toList();
            assertThat(players).as("team %s should have a playable squad", teamId)
                    .hasSizeGreaterThanOrEqualTo(11);
            List<Human> starters = players.subList(0, 11);
            Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(
                            starters.stream().map(Human::getId).toList())
                    .stream().collect(java.util.stream.Collectors.toMap(PlayerSkills::getPlayerId, value -> value));
            assertThat(skills).as("team %s should have skills for every starter", teamId).hasSize(11);

            List<RuntimeLineupSlot> slots = new ArrayList<>();
            Map<PlayerPosition, Integer> occurrences = new HashMap<>();
            for (int index = 0; index < starters.size(); index++) {
                Human player = starters.get(index);
                PlayerPosition position = FOUR_FOUR_TWO.get(index);
                int occurrence = occurrences.merge(position, 1, Integer::sum);
                slots.add(new RuntimeLineupSlot(player, skills.get(player.getId()),
                        formationData(index, player.getId()), position, occurrence));
            }
            PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(teamId)
                    .orElseGet(LeagueOutcome3IT::defaultTactic);
            result.add(new TeamSetup(teamId, teamName(teamId), support.computeTeamPower(teamId),
                    tactic, List.copyOf(slots)));
        }
        assertUniquePlayersAcrossTeams(result);
        return result;
    }

    private String teamName(long teamId) {
        String name = teamRepository.findNameById(teamId);
        return name == null ? "Team#" + teamId : name;
    }

    private static FormationData formationData(int positionIndex, long playerId) {
        FormationData data = new FormationData();
        data.setPositionIndex(positionIndex);
        data.setPlayerId(playerId);
        return data;
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

    private static void assertUniquePlayersAcrossTeams(List<TeamSetup> teams) {
        Set<Long> playerIds = new HashSet<>();
        for (TeamSetup team : teams) {
            for (RuntimeLineupSlot slot : team.slots) {
                if (!playerIds.add(slot.player().getId())) {
                    throw new IllegalArgumentException("player belongs to multiple simulated teams: "
                            + slot.player().getId());
                }
            }
        }
    }

    private AggregatedSimulation runAggregateSimulation(long competitionId, List<TeamSetup> teams) {
        List<Long> playerIds = teams.stream().flatMap(team -> team.slots.stream())
                .map(slot -> slot.player().getId()).distinct().sorted().toList();
        playerCapabilityService.preloadForCurrentThread(playerIds);
        try {
            return runPreparedSimulation(competitionId, teams);
        } finally {
            playerCapabilityService.clearPreloadedForCurrentThread();
        }
    }

    private AggregatedSimulation runPreparedSimulation(long competitionId, List<TeamSetup> teams) {
        long startedAt = System.nanoTime();
        int n = teams.size();
        int encounters = competitionFormat.get(1).encountersFor(n);
        CanonicalMatchEvaluationAdapter matchAdapter =
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
        List<CanonicalTeamEvaluation> teamEvaluations = teams.stream()
                .map(team -> runtimeInputFactory.build(team.tactic, team.slots))
                .map(matchAdapter::evaluateTeam)
                .toList();
        List<CanonicalStrength> strengths = teamEvaluations.stream()
                .map(evaluation -> new CanonicalStrength(
                        evaluation.team().rawTotals().attack(),
                        evaluation.team().rawTotals().midfield(),
                        evaluation.team().rawTotals().defense(),
                        evaluation.team().attack(),
                        evaluation.team().attackProtection()))
                .toList();

        SeasonTable[] seasons = new SeasonTable[SEASONS];
        for (int seasonIndex = 0; seasonIndex < SEASONS; seasonIndex++) {
            seasons[seasonIndex] = new SeasonTable(n);
        }

        int fixtureOrdinal = 0;
        int preparedFixtures = 0;
        int matchesScored = 0;
        for (int meeting = 0; meeting < encounters; meeting++) {
            for (int first = 0; first < n; first++) {
                for (int second = first + 1; second < n; second++) {
                    int home = meeting % 2 == 0 ? first : second;
                    int away = meeting % 2 == 0 ? second : first;
                    CanonicalMatchEvaluation evaluation = matchAdapter.evaluate(
                            teamEvaluations.get(home), teamEvaluations.get(away), MatchVenue.HOME);
                    preparedFixtures++;
                    int requestRound = fixtureOrdinal + 1;
                    for (int seasonIndex = 0; seasonIndex < SEASONS; seasonIndex++) {
                        int simulatedSeason = seasonIndex + 1;
                        // Keep LeagueOutcome2IT's key contract so both tests produce identical scores
                        // for identical inputs; only the amount of repeated evaluation work differs.
                        String fixtureKey = "LEAGUE_V2:" + competitionId + ":" + simulatedSeason + ":"
                                + fixtureOrdinal + ":" + teams.get(home).id + ":" + teams.get(away).id;
                        long seed = MatchPlanService.seedFor(fixtureKey, competitionId, simulatedSeason,
                                requestRound, teams.get(home).id, teams.get(away).id);
                        CanonicalScoreSampler.GoalSample score = scoreSampler.sample(evaluation, seed);
                        seasons[seasonIndex].apply(home, away, score.homeGoals(), score.awayGoals());
                        matchesScored++;
                    }
                    fixtureOrdinal++;
                }
            }
        }

        long[][] positionCounts = new long[n][n];
        long[] totalPoints = new long[n];
        long[] totalGF = new long[n];
        long[] totalGA = new long[n];
        long[] totalWins = new long[n];
        long[] totalDraws = new long[n];
        long[] totalLosses = new long[n];
        int[] championships = new int[n];
        for (SeasonTable season : seasons) {
            Integer[] order = season.order(teams);
            for (int position = 0; position < n; position++) {
                int team = order[position];
                positionCounts[team][position]++;
                totalPoints[team] += season.points[team];
                totalGF[team] += season.goalsFor[team];
                totalGA[team] += season.goalsAgainst[team];
                totalWins[team] += season.wins[team];
                totalDraws[team] += season.draws[team];
                totalLosses[team] += season.losses[team];
            }
            championships[order[0]]++;
        }
        return new AggregatedSimulation(positionCounts, totalPoints, totalGF, totalGA,
                totalWins, totalDraws, totalLosses, championships, matchesScored, preparedFixtures,
                (System.nanoTime() - startedAt) / 1_000_000, strengths);
    }

    private String buildReport(String competitionLine, List<Long> availableLeagues,
                               List<TeamSetup> teams, AggregatedSimulation aggregate) {
        int n = teams.size();
        String configFingerprint = fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig);
        StringBuilder report = new StringBuilder();
        report.append("# League Outcome 3 — Prepared Canonical Sampling\n\n")
                .append("Run on ").append(java.time.LocalDateTime.now()).append('\n')
                .append(competitionLine).append('\n')
                .append("Independent season tables: ").append(SEASONS).append('\n')
                .append("Teams: ").append(n).append('\n')
                .append("Canonical fixture evaluations prepared once: ").append(aggregate.preparedFixtures).append('\n')
                .append("Score samples: ").append(aggregate.matchesScored).append('\n')
                .append("Elapsed: ").append(aggregate.elapsedMs).append(" ms\n")
                .append("Engine: COMPARTMENT_V1 prepared evaluation + production CanonicalScoreSampler\n")
                .append("Weights: classpath:compartment-scoring-weights-v1.yml\n")
                .append("Config fingerprint: ").append(configFingerprint).append('\n')
                .append("Bootstrap seed: ").append(BASE_SEED).append("\n\n");
        if (availableLeagues != null) {
            report.append("## Available Leagues\n\n")
                    .append("This run includes every bootstrapped league.\n\n")
                    .append('`').append(availableLeagues).append("`\n\n");
        }
        report.append("## Average Standings After ").append(SEASONS).append(" Samples\n\n")
                .append("Each sample is a complete independent season. Each team plays ")
                .append((n - 1) * competitionFormat.get(1).encountersFor(n)).append(" matches per season.\n\n");

        double[] meanPos = new double[n];
        double[] stddevPos = new double[n];
        for (int team = 0; team < n; team++) {
            double meanSquare = 0;
            for (int position = 0; position < n; position++) {
                meanPos[team] += (position + 1) * aggregate.positionCounts[team][position];
                meanSquare += (position + 1.0) * (position + 1.0)
                        * aggregate.positionCounts[team][position];
            }
            meanPos[team] /= SEASONS;
            stddevPos[team] = Math.sqrt(Math.max(0,
                    meanSquare / SEASONS - meanPos[team] * meanPos[team]));
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> meanPos[i]));
        MarkdownTable standings = new MarkdownTable(
                List.of("Rank", "Team", "Top XI", "GK Rating", "Attack", "Midfield", "Defense",
                        "Final Attack", "Final Protection", "Tactic", "Mean Pos ± σ", "Mean Pts",
                        "Avg GF", "Avg GA", "W/D/L", "Champion"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT));
        for (int rank = 0; rank < n; rank++) {
            int team = order[rank];
            CanonicalStrength strength = aggregate.strengths.get(team);
            standings.addRow(String.valueOf(rank + 1), teams.get(team).name,
                    String.format("%.1f", teams.get(team).topXiRating),
                    String.format("%.1f", teams.get(team).goalkeeperRating()),
                    String.format("%.2f", strength.rawAttack),
                    String.format("%.2f", strength.rawMidfield),
                    String.format("%.2f", strength.rawDefense),
                    String.format("%.2f", strength.finalAttack),
                    String.format("%.2f", strength.finalProtection),
                    tacticLabel(teams.get(team).tactic),
                    String.format("%.1f ± %.1f", meanPos[team], stddevPos[team]),
                    String.format("%.1f", aggregate.totalPoints[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGF[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGA[team] / (double) SEASONS),
                    String.format("%.1f / %.1f / %.1f", aggregate.totalWins[team] / (double) SEASONS,
                            aggregate.totalDraws[team] / (double) SEASONS,
                            aggregate.totalLosses[team] / (double) SEASONS),
                    String.format("%.1f%%", aggregate.championships[team] * 100.0 / SEASONS));
        }
        report.append(standings.render()).append('\n');

        report.append("## Finishing Bands (% of seasons)\n\n");
        MarkdownTable bands = new MarkdownTable(
                List.of("Team", "1st (%)", "Top 4 (%)", "Top half (%)", "Bottom 4 (%)", "Last (%)"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int team : order) {
            bands.addRow(teams.get(team).name,
                    percentage(aggregate.positionCounts[team][0]),
                    percentage(sumPositions(aggregate.positionCounts[team], 0, Math.min(4, n))),
                    percentage(sumPositions(aggregate.positionCounts[team], 0, n / 2)),
                    percentage(sumPositions(aggregate.positionCounts[team], Math.max(0, n - 4), n)),
                    percentage(aggregate.positionCounts[team][n - 1]));
        }
        report.append(bands.render()).append('\n');

        report.append("## Position Heatmap (% of seasons at each finish position)\n\n");
        List<String> headers = new ArrayList<>();
        headers.add("Team \\ Pos");
        for (int position = 1; position <= n; position++) headers.add(String.valueOf(position));
        List<MarkdownTable.Align> aligns = new ArrayList<>();
        aligns.add(MarkdownTable.Align.LEFT);
        for (int i = 0; i < n; i++) aligns.add(MarkdownTable.Align.RIGHT);
        MarkdownTable heatmap = new MarkdownTable(headers, aligns);
        for (int team : order) {
            List<String> row = new ArrayList<>();
            row.add(teams.get(team).name);
            for (int position = 0; position < n; position++) {
                row.add(percentage(aggregate.positionCounts[team][position]));
            }
            heatmap.addRow(row.toArray(String[]::new));
        }
        report.append(heatmap.render()).append('\n')
                .append("## Method\n\n")
                .append("- Squads, tactics and canonical team evaluations are frozen for this statistical run.\n")
                .append("- **Top XI** is the sum of the ratings of the eleven starters selected by the test.\n")
                .append("- **GK Rating** is the display rating of the player used in the goalkeeper slot.\n")
                .append("- **Attack / Midfield / Defense** are the canonical raw compartment totals before mentality redistribution.\n")
                .append("- **Final Attack / Final Protection** are the two values that actually enter the goal-probability matchup.\n")
                .append("- **Tactic** lists mentality / tempo / passing / defensive line / pressing / width.\n")
                .append("- Every directed fixture probability distribution is calculated once with the production Compartment V1 formulas and current weights.\n")
                .append("- That distribution is sampled 200 times with distinct deterministic production seeds.\n")
                .append("- Sample `s` contributes only to standings table `s`; no score averaging occurs before ranking.\n")
                .append("- This test measures fixed-input score behavior, not career evolution.\n");
        return report.toString();
    }

    private void assertCompleteSimulation(List<TeamSetup> teams, AggregatedSimulation aggregate) {
        int fixturesPerSeason = teams.size() * (teams.size() - 1)
                * competitionFormat.get(1).encountersFor(teams.size()) / 2;
        assertThat(aggregate.preparedFixtures).isEqualTo(fixturesPerSeason);
        assertThat(aggregate.matchesScored).isEqualTo(SEASONS * fixturesPerSeason);
    }

    private static void writeAndPrint(Path reportPath, String report) throws Exception {
        Files.writeString(reportPath, report);
        System.out.println();
        System.out.println(report);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    private static long sumPositions(long[] values, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) total += values[i];
        return total;
    }

    private static String percentage(long count) {
        return String.format("%.1f%%", count * 100.0 / SEASONS);
    }

    private static String tacticLabel(PersonalizedTactic tactic) {
        return String.join(" / ",
                tacticValue(tactic.getMentality(), "Balanced"),
                tacticValue(tactic.getTempo(), "Standard"),
                tacticValue(tactic.getPassingType(), "Normal"),
                tacticValue(tactic.getDefensiveLine(), "Standard"),
                tacticValue(tactic.getPressing(), "Standard"),
                tacticValue(tactic.getWidth(), "Balanced"));
    }

    private static String tacticValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record TeamSetup(long id, String name, double topXiRating, PersonalizedTactic tactic,
                             List<RuntimeLineupSlot> slots) {
        private double goalkeeperRating() {
            return slots.stream()
                    .filter(slot -> slot.usedPosition() == PlayerPosition.GK)
                    .mapToDouble(slot -> slot.player().getRating())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("team has no goalkeeper slot: " + id));
        }
    }

    private record CanonicalStrength(double rawAttack, double rawMidfield, double rawDefense,
                                     double finalAttack, double finalProtection) {}

    private record AggregatedSimulation(long[][] positionCounts, long[] totalPoints, long[] totalGF,
                                        long[] totalGA, long[] totalWins, long[] totalDraws,
                                        long[] totalLosses, int[] championships, int matchesScored,
                                        int preparedFixtures, long elapsedMs,
                                        List<CanonicalStrength> strengths) {
        private AggregatedSimulation {
            strengths = List.copyOf(strengths);
        }
    }

    private static final class SeasonTable {
        private final int[] points;
        private final int[] goalsFor;
        private final int[] goalsAgainst;
        private final int[] wins;
        private final int[] draws;
        private final int[] losses;

        private SeasonTable(int teams) {
            points = new int[teams];
            goalsFor = new int[teams];
            goalsAgainst = new int[teams];
            wins = new int[teams];
            draws = new int[teams];
            losses = new int[teams];
        }

        private void apply(int home, int away, int homeGoals, int awayGoals) {
            goalsFor[home] += homeGoals;
            goalsAgainst[home] += awayGoals;
            goalsFor[away] += awayGoals;
            goalsAgainst[away] += homeGoals;
            if (homeGoals > awayGoals) {
                points[home] += 3;
                wins[home]++;
                losses[away]++;
            } else if (homeGoals < awayGoals) {
                points[away] += 3;
                wins[away]++;
                losses[home]++;
            } else {
                points[home]++;
                points[away]++;
                draws[home]++;
                draws[away]++;
            }
        }

        private Integer[] order(List<TeamSetup> teams) {
            Integer[] order = new Integer[points.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (first, second) -> {
                if (points[first] != points[second]) return Integer.compare(points[second], points[first]);
                int firstGoalDifference = goalsFor[first] - goalsAgainst[first];
                int secondGoalDifference = goalsFor[second] - goalsAgainst[second];
                if (firstGoalDifference != secondGoalDifference) {
                    return Integer.compare(secondGoalDifference, firstGoalDifference);
                }
                if (goalsFor[first] != goalsFor[second]) {
                    return Integer.compare(goalsFor[second], goalsFor[first]);
                }
                return teams.get(first).name.compareTo(teams.get(second).name);
            });
            return order;
        }
    }
}
