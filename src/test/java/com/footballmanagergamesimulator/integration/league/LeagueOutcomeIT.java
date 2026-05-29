package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.tournament.TournamentEngine;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.OutcomeTestSupport;
import com.footballmanagergamesimulator.testutil.TeamSetup;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-run league outcome simulation: for a given league competition, runs
 * {@code SEASONS} full round-robin (home + away) campaigns with a seeded RNG,
 * aggregates standings per season, and reports each team's average finishing
 * position, average points, win/draw/loss split, and title probability.
 *
 * <p>Uses {@code calculateScores} directly (the synthetic-engine pure
 * function) — bypasses the heavy season-transition / injuries / training
 * paths so it runs in seconds, not minutes.
 *
 * <p>Team power = sum of top-11 player ratings (raw, no morale/fitness
 * adjustment). This is the same "simple team rating" path used by the
 * AI-vs-AI batched simulator at runtime, just minus the per-tactic
 * positional constraint. Good enough for relative league-wide ranking.
 *
 * <h2>How to run</h2>
 * <pre>
 *   # default — picks the lowest-id league competition
 *   mvn verify -Ptune -Dit.test=LeagueOutcomeIT
 *
 *   # pick a specific league by id
 *   mvn verify -Ptune -Dit.test=LeagueOutcomeIT -Dleague.id=3
 * </pre>
 *
 * <p>Output: {@code target/league-outcome-{leagueId}.md} with sections for
 * available leagues, power ranking, mean standings, title probability, and
 * a position heatmap.
 *
 * <p>Results are <b>fully deterministic</b>: same league + same engine config
 * + same {@code BASE_SEED} → identical numbers across runs. Teams are sorted
 * by id before simulation so DB row order can't perturb the RNG stream.
 *
 * <p>Gated behind {@code mvn verify -Ptune}.
 */
@SpringBootTest
// Seed the bootstrap squad generator so the same squad ratings are produced
// across separate `mvn` invocations — without this the player attributes vary
// per JVM start and the team powers shift slightly run to run.
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("League outcome — N seasons of round-robin, mean position + points per team")
class LeagueOutcomeIT {

    private static final int SEASONS = 200;
    private static final long BASE_SEED = 20260528L;

    /** Override the league via {@code -Dleague.id=X}. If absent, picks the
     *  lowest-id league competition. */
    private static final String LEAGUE_ID_PROPERTY = "league.id";

    /** Comma-separated list of team IDs for the custom-league test. Required
     *  by {@link #simulateCustomTeamsAndReport}; the test is skipped if absent. */
    private static final String TEAM_IDS_PROPERTY = "team.ids";

    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private GameStateService gameState;
    @Autowired private OutcomeTestSupport support;
    @Autowired private TournamentEngine engine;

    @Test
    @DisplayName("Simulate one league for N seasons + write target/league-outcome-{leagueId}.md")
    void simulateLeagueAndReport() throws Exception {
        // Discover all available leagues so we can list them in the report
        // and so the property-override path can validate the user's choice.
        List<Long> availableLeagues = gameState.getLeagueCompetitionIdsCached()
                .stream().sorted().toList();
        assertThat(availableLeagues)
                .as("at least one league competition must be bootstrapped")
                .isNotEmpty();

        long compId = resolveLeagueId(availableLeagues);
        int season = gameState.currentSeason();
        Path reportPath = Path.of("target", "league-outcome-" + compId + ".md");

        // ---- 1. Load teams + compute power ----
        List<TeamSetup> teams = loadTeams(compId, season);
        assertThat(teams).as("league must have teams").isNotEmpty();

        // ---- 2. Simulate N seasons + 3. Build report ----
        AggregatedSimulation agg = runAggregateSimulation(teams);
        String md = buildReport(
                "Competition: id=" + compId + ", season=" + season,
                availableLeagues,
                teams, agg);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    /**
     * Simulate a user-defined league: any even number of teams chosen by ID
     * from anywhere in the DB. Skipped if {@code -Dteam.ids=...} is not set.
     *
     * <p>Examples:
     * <pre>
     *   # 6-team custom league mixing teams from different real leagues
     *   mvn verify -Ptune -Dit.test=LeagueOutcomeIT#simulateCustomTeamsAndReport \
     *       -Dteam.ids=1,5,8,12,15,20
     *
     *   # 4-team mini league
     *   mvn verify -Ptune -Dit.test=LeagueOutcomeIT#simulateCustomTeamsAndReport \
     *       -Dteam.ids=2,7,11,16
     * </pre>
     *
     * <p>Constraints:
     * <ul>
     *   <li>Even count (so round-robin doesn't need rest weeks) — odd → fail-fast</li>
     *   <li>Every ID must exist in {@code teamRepo} — unknown → fail-fast</li>
     *   <li>Minimum 2 teams</li>
     * </ul>
     */
    @Test
    @DisplayName("Simulate custom team list — supply via -Dteam.ids=ID1,ID2,...")
    void simulateCustomTeamsAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(idsProperty);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException(
                    "Need at least 2 teams; got " + teamIds.size() + " (input: " + idsProperty + ")");
        }
        if (teamIds.size() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Team count must be even (round-robin without rest weeks). "
                            + "Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }

        // ---- 1. Load + sort teams for deterministic ordering ----
        List<TeamSetup> teams = support.loadTeamsByIds(teamIds);

        // ---- 2. Simulate N seasons + 3. Build report ----
        AggregatedSimulation agg = runAggregateSimulation(teams);

        String idsLabel = teams.stream()
                .map(t -> String.valueOf(t.id()))
                .collect(java.util.stream.Collectors.joining(", "));
        String idsForFilename = teams.stream()
                .map(t -> String.valueOf(t.id()))
                .collect(java.util.stream.Collectors.joining("_"));
        Path reportPath = Path.of("target", "league-outcome-custom-" + idsForFilename + ".md");

        String md = buildReport(
                "Competition: CUSTOM league of " + teams.size() + " teams (IDs: " + idsLabel + ")",
                null,
                teams, agg);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    /**
     * Run {@link #SEASONS} seasons of round-robin against the given teams.
     * Returns aggregated counters used by the report builder. Seeded RNG is
     * applied at start and restored on exit so other tests in the suite are
     * unaffected.
     */
    private AggregatedSimulation runAggregateSimulation(List<TeamSetup> teams) {
        int n = teams.size();
        long[][] positionCounts = new long[n][n];
        long[] totalPoints = new long[n];
        long[] totalGF = new long[n];
        long[] totalGA = new long[n];
        long[] totalWins = new long[n];
        long[] totalDraws = new long[n];
        long[] totalLosses = new long[n];
        int[] championships = new int[n];

        double[] powers = new double[n];
        for (int i = 0; i < n; i++) powers[i] = teams.get(i).power();

        matchSim.setRandomForTesting(new Random(BASE_SEED));
        long t0 = System.nanoTime();
        try {
            for (int s = 0; s < SEASONS; s++) {
                SeasonOutcome outcome = runOneSeason(teams, powers);
                for (int finalPos = 0; finalPos < n; finalPos++) {
                    int teamIdx = outcome.finalOrder[finalPos];
                    positionCounts[teamIdx][finalPos]++;
                    totalPoints[teamIdx] += outcome.points[teamIdx];
                    totalGF[teamIdx] += outcome.goalsFor[teamIdx];
                    totalGA[teamIdx] += outcome.goalsAgainst[teamIdx];
                    totalWins[teamIdx] += outcome.wins[teamIdx];
                    totalDraws[teamIdx] += outcome.draws[teamIdx];
                    totalLosses[teamIdx] += outcome.losses[teamIdx];
                }
                championships[outcome.finalOrder[0]]++;
            }
        } finally {
            matchSim.setRandomForTesting(new Random());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        return new AggregatedSimulation(positionCounts, totalPoints, totalGF, totalGA,
                totalWins, totalDraws, totalLosses, championships, elapsedMs);
    }

    /**
     * Resolve which league to simulate. {@code -Dleague.id=X} overrides;
     * absent → first available. Validates that the chosen ID exists.
     */
    private long resolveLeagueId(List<Long> availableLeagues) {
        String override = System.getProperty(LEAGUE_ID_PROPERTY);
        if (override == null || override.isBlank()) {
            return availableLeagues.get(0);
        }
        long requested;
        try {
            requested = Long.parseLong(override.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid -Dleague.id=" + override + " — must be an integer. "
                            + "Available leagues: " + availableLeagues);
        }
        if (!availableLeagues.contains(requested)) {
            throw new IllegalArgumentException(
                    "-Dleague.id=" + requested + " is not a league competition. "
                            + "Available: " + availableLeagues);
        }
        return requested;
    }

    // ==================== TEAM LOADING ====================

    private List<TeamSetup> loadTeams(long compId, int season) {
        List<CompetitionTeamInfo> ctis = ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season);
        // Distinct team IDs — DB rows can repeat (round-robin scheduling).
        // Sort by id so the simulation order is identical across runs; without
        // this the RNG stream consumption would depend on incidental row order.
        java.util.TreeSet<Long> teamIds = new java.util.TreeSet<>();
        for (CompetitionTeamInfo cti : ctis) {
            if (cti.getTeamId() > 0) teamIds.add(cti.getTeamId());
        }
        List<TeamSetup> out = new ArrayList<>(teamIds.size());
        for (long teamId : teamIds) {
            double power = support.computeTeamPower(teamId);
            String name = teamRepo.findNameById(teamId);
            out.add(new TeamSetup(teamId, name == null ? "Team#" + teamId : name, power));
        }
        return out;
    }

    // ==================== SEASON SIMULATION ====================

    /** One round-robin season via the shared engine. Returns final ordering + per-team stats. */
    private SeasonOutcome runOneSeason(List<TeamSetup> teams, double[] powers) {
        int n = teams.size();
        List<Integer> teamIdx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) teamIdx.add(i);

        TournamentEngine.LeagueTally tally = engine.playDoubleRoundRobin(teamIdx, powers);
        int[] points = tally.points();
        int[] goalsFor = tally.goalsFor();
        int[] goalsAgainst = tally.goalsAgainst();
        int[] wins = tally.wins();
        int[] draws = tally.draws();
        int[] losses = tally.losses();

        // Sort indices by (points desc, GD desc, GF desc, name asc).
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> {
            if (points[a] != points[b]) return points[b] - points[a];
            int gdA = goalsFor[a] - goalsAgainst[a];
            int gdB = goalsFor[b] - goalsAgainst[b];
            if (gdA != gdB) return gdB - gdA;
            if (goalsFor[a] != goalsFor[b]) return goalsFor[b] - goalsFor[a];
            return teams.get(a).name().compareTo(teams.get(b).name());
        });

        int[] finalOrder = new int[n];
        for (int i = 0; i < n; i++) finalOrder[i] = indices[i];

        return new SeasonOutcome(finalOrder, points, goalsFor, goalsAgainst, wins, draws, losses);
    }

    // ==================== REPORT ====================

    /**
     * Build the markdown report.
     *
     * @param competitionLine   line shown under the run timestamp, e.g.
     *                          {@code "Competition: id=1, season=1"} or
     *                          {@code "Competition: CUSTOM league of 6 teams ..."}
     * @param availableLeagues  if non-null, an "Available Leagues" section is
     *                          included so users know what to pass to
     *                          {@code -Dleague.id}. Pass {@code null} for the
     *                          custom-teams test where that section is irrelevant.
     */
    private static String buildReport(String competitionLine,
                                      List<Long> availableLeagues,
                                      List<TeamSetup> teams,
                                      AggregatedSimulation agg) {
        long[][] positionCounts = agg.positionCounts();
        long[] totalPoints = agg.totalPoints();
        long[] totalGF = agg.totalGF();
        long[] totalGA = agg.totalGA();
        long[] totalWins = agg.totalWins();
        long[] totalDraws = agg.totalDraws();
        long[] totalLosses = agg.totalLosses();
        int[] championships = agg.championships();
        long elapsedMs = agg.elapsedMs();

        int n = teams.size();
        StringBuilder sb = new StringBuilder();
        sb.append("# League Outcome Simulation\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append(competitionLine).append('\n');
        sb.append("Seasons simulated: ").append(SEASONS).append('\n');
        sb.append("Teams: ").append(n).append('\n');
        sb.append("Elapsed: ").append(elapsedMs).append(" ms\n");
        sb.append("Seed: ").append(BASE_SEED).append(" (deterministic — same seed → same numbers)\n\n");

        // ---- Available leagues (omitted for custom-teams test) ----
        if (availableLeagues != null) {
            sb.append("## Available Leagues\n\n");
            sb.append("Run with `-Dleague.id=X` to simulate a different one. ");
            sb.append("Currently simulating one of these.\n\n");
            sb.append("`").append(availableLeagues).append("`\n\n");
        }

        // ---- Mean position table (sorted by mean position ASC) ----
        double[] meanPos = new double[n];
        double[] meanPts = new double[n];
        double[] stddevPos = new double[n];
        for (int t = 0; t < n; t++) {
            double mp = 0, mp2 = 0;
            for (int pos = 0; pos < n; pos++) {
                mp += (pos + 1) * positionCounts[t][pos];
                mp2 += (pos + 1.0) * (pos + 1.0) * positionCounts[t][pos];
            }
            meanPos[t] = mp / SEASONS;
            stddevPos[t] = Math.sqrt(Math.max(0, mp2 / SEASONS - meanPos[t] * meanPos[t]));
            meanPts[t] = totalPoints[t] / (double) SEASONS;
        }

        Integer[] orderByMeanPos = new Integer[n];
        for (int i = 0; i < n; i++) orderByMeanPos[i] = i;
        java.util.Arrays.sort(orderByMeanPos, (a, b) -> Double.compare(meanPos[a], meanPos[b]));

        sb.append("## Average Standings After ").append(SEASONS).append(" Seasons\n\n");
        sb.append("Sorted by mean finishing position. ");
        sb.append("Each team plays ").append((n - 1) * 2).append(" matches per season ")
                .append("(home + away vs every other team).\n\n");
        MarkdownTable standings = new MarkdownTable(
                List.of("Rank", "Team", "Power", "Mean Pos ± σ", "Mean Pts",
                        "Avg GF", "Avg GA", "W/D/L per season", "Champion"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT));
        int displayRank = 1;
        for (int t : orderByMeanPos) {
            double wins = totalWins[t] / (double) SEASONS;
            double draws = totalDraws[t] / (double) SEASONS;
            double losses = totalLosses[t] / (double) SEASONS;
            double gfPer = totalGF[t] / (double) SEASONS;
            double gaPer = totalGA[t] / (double) SEASONS;
            standings.addRow(
                    String.valueOf(displayRank++),
                    teams.get(t).name(),
                    String.format("%.0f", teams.get(t).power()),
                    String.format("%.1f ± %.1f", meanPos[t], stddevPos[t]),
                    String.format("%.1f", meanPts[t]),
                    String.format("%.1f", gfPer),
                    String.format("%.1f", gaPer),
                    String.format("%.1f / %.1f / %.1f", wins, draws, losses),
                    String.format("%.1f%%", championships[t] * 100.0 / SEASONS));
        }
        sb.append(standings.render()).append('\n');

        // ---- Title / top-K probabilities ----
        sb.append("## Finishing Bands (% of seasons each team finished within band)\n\n");
        int topMid = Math.max(1, n / 3);
        int botCut = n - Math.max(1, n / 3);
        MarkdownTable bands = new MarkdownTable(
                List.of("Team", "1st (%)", "Top " + topMid + " (%)", "Top half (%)",
                        "Bottom " + (n - botCut) + " (%)", "Last (%)"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int t : orderByMeanPos) {
            double champ = positionCounts[t][0] * 100.0 / SEASONS;
            double topMidPct = 0, halfPct = 0, botPct = 0;
            for (int pos = 0; pos < topMid; pos++) topMidPct += positionCounts[t][pos];
            for (int pos = 0; pos < n / 2; pos++) halfPct += positionCounts[t][pos];
            for (int pos = botCut; pos < n; pos++) botPct += positionCounts[t][pos];
            double last = positionCounts[t][n - 1] * 100.0 / SEASONS;
            bands.addRow(
                    teams.get(t).name(),
                    String.format("%.1f%%", champ),
                    String.format("%.1f%%", topMidPct * 100.0 / SEASONS),
                    String.format("%.1f%%", halfPct * 100.0 / SEASONS),
                    String.format("%.1f%%", botPct * 100.0 / SEASONS),
                    String.format("%.1f%%", last));
        }
        sb.append(bands.render()).append('\n');

        // ---- Position heatmap (% finishes per position) ----
        sb.append("## Position Heatmap (% of seasons at each finish position)\n\n");
        sb.append("Rows = team (sorted by mean position). Columns = final position. ");
        sb.append("Cell = how often this team landed at this position.\n\n");
        List<String> heatHeaders = new ArrayList<>(n + 1);
        List<MarkdownTable.Align> heatAlignments = new ArrayList<>(n + 1);
        heatHeaders.add("Team \\ Pos");
        heatAlignments.add(MarkdownTable.Align.LEFT);
        for (int p = 1; p <= n; p++) {
            heatHeaders.add(String.valueOf(p));
            heatAlignments.add(MarkdownTable.Align.RIGHT);
        }
        MarkdownTable heatmap = new MarkdownTable(heatHeaders, heatAlignments);
        for (int t : orderByMeanPos) {
            String[] row = new String[n + 1];
            row[0] = teams.get(t).name();
            for (int pos = 0; pos < n; pos++) {
                double pct = positionCounts[t][pos] * 100.0 / SEASONS;
                row[pos + 1] = pct < 0.5 ? "." : String.format("%.0f%%", pct);
            }
            heatmap.addRow(row);
        }
        sb.append(heatmap.render()).append('\n');

        sb.append("## How to read this report\n\n");
        sb.append("- **Power** = sum of top-11 player ratings (no morale/fitness ");
        sb.append("adjustments). A 1.5× power gap usually translates into 5+ positions ");
        sb.append("of mean-rank separation over many seasons.\n");
        sb.append("- **Mean Pos ± σ**: lower mean = better. A tight σ (e.g. ±0.5) means ");
        sb.append("the team's outcome is stable; large σ (e.g. ±3) means season-to-season ");
        sb.append("luck swings dominate the final standing.\n");
        sb.append("- **Champion %**: how often each team finished 1st. Sums to 100% across all teams.\n");
        sb.append("- **Heatmap**: a strong team should show concentration near position 1; ");
        sb.append("a noisy mid-table team should show a wide spread.\n");

        return sb.toString();
    }

    // ==================== INNER TYPES ====================

    private record SeasonOutcome(int[] finalOrder,
                                  int[] points, int[] goalsFor, int[] goalsAgainst,
                                  int[] wins, int[] draws, int[] losses) {}

    /** N-season aggregated counters passed to {@link #buildReport}. */
    private record AggregatedSimulation(
            long[][] positionCounts,
            long[] totalPoints,
            long[] totalGF,
            long[] totalGA,
            long[] totalWins,
            long[] totalDraws,
            long[] totalLosses,
            int[] championships,
            long elapsedMs) {}
}
