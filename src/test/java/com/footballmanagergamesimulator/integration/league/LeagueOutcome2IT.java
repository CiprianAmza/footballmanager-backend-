package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeScore;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeScoringService;
import com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.FormationData;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-run league outcome report using the same presentation as LeagueOutcomeIT,
 * but with every match scored by the production Compartment V1 runtime scorer.
 *
 * <p>This deliberately does not call TournamentEngine or MatchSimulationService.
 * It builds the same deterministic round-robin campaign in the test harness and
 * sends every fixture through CanonicalRuntimeScoringService, using the current
 * player skills, fitness, morale, tactics and compartment configuration. The
 * primary test discovers and simulates every bootstrapped league, not only the
 * lowest-id competition.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bootstrap.seed=20260528"
})
@DisplayName("League outcome 2 — 200 seasons with production Compartment V1 scoring")
class LeagueOutcome2IT {

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
    @Autowired private CanonicalRuntimeScoringService scoringService;
    @Autowired private CanonicalScoringFingerprintService fingerprintService;
    @Autowired private GameStateService gameState;
    @Autowired private PlayerCapabilityService playerCapabilityService;
    @Autowired private OutcomeTestSupport support;

    @Test
    @DisplayName("200-season report for every league uses the production canonical scorer")
    void simulateAllLeaguesAndReportWithCanonicalScorer() throws Exception {
        List<Long> availableLeagues = availableLeagues();
        int season = gameState.currentSeason();

        StringBuilder combined = new StringBuilder("# League Outcome 2 — All Leagues\n\n")
                .append("Leagues: ").append(availableLeagues).append('\n')
                .append("Seasons simulated per league: ").append(SEASONS).append("\n\n");
        for (long competitionId : availableLeagues) {
            Competition league = competitionRepository.findById(competitionId)
                    .orElseThrow(() -> new IllegalStateException("league does not exist: " + competitionId));
            List<TeamSetupV2> teams = loadTeams(league.getId(), season);
            assertThat(teams).as("league %s must have teams", competitionId).isNotEmpty();

            AggregatedSimulation aggregate = runAggregateSimulation(league.getId(), teams);
            String report = buildReport("Competition: id=" + league.getId() + ", name="
                            + league.getName() + ", season=" + season,
                    availableLeagues, teams, aggregate);
            Files.writeString(Path.of("target", "league-outcome-2-" + league.getId() + ".md"), report);
            combined.append(report).append("\n\n---\n\n");

            assertCompleteSimulation(teams, aggregate);
        }
        writeAndPrint(Path.of("target", "league-outcome-2-all.md"), combined.toString());
    }

    @Test
    @DisplayName("200-season canonical report for -Dteam.ids=ID1,ID2,...")
    void simulateCustomTeamsAndReport() throws Exception {
        String rawIds = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(rawIds != null && !rawIds.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");
        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(rawIds);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 teams; got " + teamIds.size());
        }
        List<TeamSetupV2> teams = loadTeamsByIds(teamIds);
        long scoringCompetitionId = availableLeagues().get(0);
        AggregatedSimulation aggregate = runAggregateSimulation(scoringCompetitionId, teams);
        String label = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining(", "));
        String filenameIds = teams.stream().map(team -> Long.toString(team.id))
                .collect(java.util.stream.Collectors.joining("_"));
        String report = buildReport("Competition: CUSTOM league of " + teams.size()
                        + " teams (IDs: " + label + ")", null, teams, aggregate);
        writeAndPrint(Path.of("target", "league-outcome-2-custom-" + filenameIds + ".md"), report);
        assertCompleteSimulation(teams, aggregate);
    }

    private List<Long> availableLeagues() {
        List<Long> leagues = gameState.getLeagueCompetitionIdsCached().stream().sorted().toList();
        assertThat(leagues).as("at least one league competition must be bootstrapped").isNotEmpty();
        return leagues;
    }

    private List<TeamSetupV2> loadTeams(long competitionId, int season) {
        List<Long> teamIds = competitionTeamInfoRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .map(CompetitionTeamInfo::getTeamId)
                .filter(id -> id > 0).distinct().sorted().toList();
        return loadTeamsByIds(teamIds);
    }

    private List<TeamSetupV2> loadTeamsByIds(List<Long> teamIds) {
        List<TeamSetupV2> result = new ArrayList<>();
        for (long teamId : teamIds.stream().distinct().sorted().toList()) {
            assertThat(teamRepository.existsById(teamId)).as("team %s must exist", teamId).isTrue();
            List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                    .stream()
                    .filter(player -> !player.isRetired())
                    .sorted(Comparator.comparingDouble(Human::getRating)
                            .reversed().thenComparingLong(Human::getId))
                    .toList();
            assertThat(players).as("team %s should have a playable squad", teamId).hasSizeGreaterThanOrEqualTo(11);
            List<Human> starters = players.subList(0, 11);
            Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(
                            starters.stream().map(Human::getId).toList())
                    .stream().collect(java.util.stream.Collectors.toMap(PlayerSkills::getPlayerId, value -> value));
            assertThat(skills).as("team %s should have skills for every starter", teamId)
                    .hasSize(11);

            List<RuntimeLineupSlot> slots = new ArrayList<>();
            Map<PlayerPosition, Integer> occurrences = new HashMap<>();
            for (int index = 0; index < starters.size(); index++) {
                Human player = starters.get(index);
                PlayerPosition position = FOUR_FOUR_TWO.get(index);
                int occurrence = occurrences.merge(position, 1, Integer::sum);
                slots.add(new RuntimeLineupSlot(player, skills.get(player.getId()), formationData(index, player.getId()),
                        position, occurrence));
            }
            PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(teamId)
                    .orElseGet(LeagueOutcome2IT::defaultTactic);
            result.add(new TeamSetupV2(teamId, teamName(teamId),
                    support.computeTeamPower(teamId), tactic, List.copyOf(slots)));
        }
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

    private AggregatedSimulation runAggregateSimulation(long competitionId, List<TeamSetupV2> teams) {
        List<Long> playerIds = teams.stream().flatMap(team -> team.slots.stream())
                .map(slot -> slot.player().getId()).distinct().sorted().toList();
        playerCapabilityService.preloadForCurrentThread(playerIds);
        try {
            return runAggregateSimulationPreloaded(competitionId, teams);
        } finally {
            playerCapabilityService.clearPreloadedForCurrentThread();
        }
    }

    private AggregatedSimulation runAggregateSimulationPreloaded(long competitionId, List<TeamSetupV2> teams) {
        long startedAt = System.nanoTime();
        int n = teams.size();
        int encounters = competitionFormat.get(1).encountersFor(n);
        long[][] positionCounts = new long[n][n];
        long[] totalPoints = new long[n];
        long[] totalGF = new long[n];
        long[] totalGA = new long[n];
        long[] totalWins = new long[n];
        long[] totalDraws = new long[n];
        long[] totalLosses = new long[n];
        int[] championships = new int[n];
        int matchesScored = 0;
        int canonicalFailures = 0;

        for (int season = 1; season <= SEASONS; season++) {
            int[] points = new int[n];
            int[] goalsFor = new int[n];
            int[] goalsAgainst = new int[n];
            int[] wins = new int[n];
            int[] draws = new int[n];
            int[] losses = new int[n];
            int round = 0;
            for (int meeting = 0; meeting < encounters; meeting++) {
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        TeamSetupV2 home = meeting % 2 == 0 ? teams.get(i) : teams.get(j);
                        TeamSetupV2 away = meeting % 2 == 0 ? teams.get(j) : teams.get(i);
                        String fixtureKey = "LEAGUE_V2:" + competitionId + ":" + season + ":" + round++
                                + ":" + home.id + ":" + away.id;
                        var request = CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                                fixtureKey, competitionId, season, round, home.id, away.id,
                                home.tactic, away.tactic, home.slots, away.slots);
                        CanonicalRuntimeScore score = scoringService.score(() -> request);
                        applyResult(homeIndex(home, teams), awayIndex(away, teams), score.homeGoals(),
                                score.awayGoals(), points, goalsFor, goalsAgainst, wins, draws, losses);
                        matchesScored++;
                    }
                }
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> {
                if (points[a] != points[b]) return Integer.compare(points[b], points[a]);
                int gdA = goalsFor[a] - goalsAgainst[a];
                int gdB = goalsFor[b] - goalsAgainst[b];
                if (gdA != gdB) return Integer.compare(gdB, gdA);
                if (goalsFor[a] != goalsFor[b]) return Integer.compare(goalsFor[b], goalsFor[a]);
                return teams.get(a).name.compareTo(teams.get(b).name);
            });
            for (int position = 0; position < n; position++) {
                int team = order[position];
                positionCounts[team][position]++;
                totalPoints[team] += points[team];
                totalGF[team] += goalsFor[team];
                totalGA[team] += goalsAgainst[team];
                totalWins[team] += wins[team];
                totalDraws[team] += draws[team];
                totalLosses[team] += losses[team];
            }
            championships[order[0]]++;
        }
        return new AggregatedSimulation(positionCounts, totalPoints, totalGF, totalGA,
                totalWins, totalDraws, totalLosses, championships, matchesScored, canonicalFailures,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static int homeIndex(TeamSetupV2 team, List<TeamSetupV2> teams) {
        return index(team, teams);
    }

    private static int awayIndex(TeamSetupV2 team, List<TeamSetupV2> teams) {
        return index(team, teams);
    }

    private static int index(TeamSetupV2 team, List<TeamSetupV2> teams) {
        for (int i = 0; i < teams.size(); i++) if (teams.get(i).id == team.id) return i;
        throw new IllegalArgumentException("team not in league: " + team.id);
    }

    private static void applyResult(int home, int away, int homeGoals, int awayGoals,
                                    int[] points, int[] goalsFor, int[] goalsAgainst,
                                    int[] wins, int[] draws, int[] losses) {
        goalsFor[home] += homeGoals;
        goalsAgainst[home] += awayGoals;
        goalsFor[away] += awayGoals;
        goalsAgainst[away] += homeGoals;
        if (homeGoals > awayGoals) { points[home] += 3; wins[home]++; losses[away]++; }
        else if (homeGoals < awayGoals) { points[away] += 3; wins[away]++; losses[home]++; }
        else { points[home]++; points[away]++; draws[home]++; draws[away]++; }
    }

    private String buildReport(String competitionLine, List<Long> availableLeagues,
                               List<TeamSetupV2> teams,
                               AggregatedSimulation aggregate) {
        int n = teams.size();
        String configFingerprint = fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig);
        StringBuilder report = new StringBuilder();
        report.append("# League Outcome Simulation\n\n")
                .append("Run on ").append(java.time.LocalDateTime.now()).append('\n')
                .append(competitionLine).append('\n')
                .append("Seasons simulated: ").append(SEASONS).append('\n')
                .append("Teams: ").append(n).append('\n')
                .append("Elapsed: ").append(aggregate.elapsedMs).append(" ms\n")
                .append("Engine: COMPARTMENT_V1 (compartment-score-1)\n")
                .append("Weights: classpath:compartment-scoring-weights-v1.yml\n")
                .append("Config fingerprint: ").append(configFingerprint).append('\n')
                .append("Seed: ").append(BASE_SEED).append(" (deterministic — same seed → same numbers)\n\n");
        if (availableLeagues != null) {
            report.append("## Available Leagues\n\n")
                    .append("This run includes every bootstrapped league.\n\n")
                    .append('`').append(availableLeagues).append("`\n\n");
        }
        report.append("## Average Standings After ").append(SEASONS).append(" Seasons\n\n")
                .append("Sorted by mean finishing position. Each team plays ")
                .append((n - 1) * competitionFormatValue(n)).append(" matches per season.\n\n");

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
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> meanPos[i]));
        MarkdownTable standings = new MarkdownTable(
                List.of("Rank", "Team", "Power", "Mean Pos ± σ", "Mean Pts", "Avg GF", "Avg GA", "W/D/L", "Champion"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        for (int rank = 0; rank < n; rank++) {
            int team = order[rank];
            standings.addRow(String.valueOf(rank + 1), teams.get(team).name,
                    String.format("%.0f", teams.get(team).power),
                    String.format("%.1f ± %.1f", meanPos[team], stddevPos[team]),
                    String.format("%.1f", aggregate.totalPoints[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGF[team] / (double) SEASONS),
                    String.format("%.1f", aggregate.totalGA[team] / (double) SEASONS),
                    String.format("%.1f / %.1f / %.1f", aggregate.totalWins[team] / (double) SEASONS,
                            aggregate.totalDraws[team] / (double) SEASONS, aggregate.totalLosses[team] / (double) SEASONS),
                    String.format("%.1f%%", aggregate.championships[team] * 100.0 / SEASONS));
        }
        report.append(standings.render()).append('\n');

        report.append("## Finishing Bands (% of seasons each team finished within band)\n\n");
        MarkdownTable bands = new MarkdownTable(List.of("Team", "1st (%)", "Top 4 (%)", "Top half (%)", "Bottom 4 (%)", "Last (%)"),
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

        report.append("## Position Heatmap (% of seasons at each finish position)\n\n")
                .append("Rows = team (sorted by mean position). Columns = final position.\n\n");
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
            for (int position = 0; position < n; position++) row.add(percentage(aggregate.positionCounts[team][position]));
            heatmap.addRow(row.toArray(String[]::new));
        }
        report.append(heatmap.render()).append('\n')
                .append("## How to read this report\n\n")
                .append("- **Power** is the legacy display metric only; match scores use the canonical compartment inputs.\n")
                .append("- **COMPARTMENT_V1** uses the production `CanonicalRuntimeScoringService`.\n")
                .append("- Every active scoring coefficient comes from `compartment-scoring-weights-v1.yml`; the fingerprint above identifies the exact bound configuration.\n")
                .append("- **Champion %** sums to 100% across the league.\n");
        return report.toString();
    }

    private void assertCompleteSimulation(List<TeamSetupV2> teams, AggregatedSimulation aggregate) {
        int expectedMatches = SEASONS * teams.size() * (teams.size() - 1)
                * competitionFormat.get(1).encountersFor(teams.size()) / 2;
        assertThat(aggregate.matchesScored).isEqualTo(expectedMatches);
        assertThat(aggregate.canonicalFailures).isZero();
    }

    private static void writeAndPrint(Path reportPath, String report) throws Exception {
        Files.writeString(reportPath, report);
        System.out.println();
        System.out.println(report);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    private int competitionFormatValue(int teamCount) {
        return competitionFormat.get(1).encountersFor(teamCount);
    }

    private static long sumPositions(long[] values, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) total += values[i];
        return total;
    }

    private static String percentage(long count) {
        return String.format("%.1f%%", count * 100.0 / SEASONS);
    }

    private record TeamSetupV2(long id, String name, double power, PersonalizedTactic tactic,
                               List<RuntimeLineupSlot> slots) {}

    private record AggregatedSimulation(long[][] positionCounts, long[] totalPoints, long[] totalGF,
                                        long[] totalGA, long[] totalWins, long[] totalDraws,
                                        long[] totalLosses, int[] championships, int matchesScored,
                                        int canonicalFailures, long elapsedMs) {}
}
