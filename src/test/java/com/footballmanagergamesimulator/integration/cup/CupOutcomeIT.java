package com.footballmanagergamesimulator.integration.cup;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
import com.footballmanagergamesimulator.service.tournament.TournamentEngine;
import com.footballmanagergamesimulator.testutil.BracketUtil;
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
 * Long-run knockout-cup outcome simulation: given an even number of teams
 * (by ID), builds a single-elimination bracket and simulates {@code TOURNAMENTS}
 * cups with a seeded RNG, then reports how often each team wins the cup, reaches
 * the final / semifinal / earlier stages, plus average matches played/won.
 *
 * <p>Counterpart to {@link com.footballmanagergamesimulator.integration.league.LeagueOutcomeIT}
 * (which does round-robin leagues). Here the format is knockout, single-leg,
 * with draws resolved by the same power-weighted AET tiebreak the live engine
 * uses in {@code MatchRoundSimulator} (see
 * {@link MatchEngineConfig.Knockout}: {@code base + weight * myPower/totalPower}).
 *
 * <p>Non-power-of-2 even counts (6, 10, 12, ...) are padded to the next power of
 * two by giving <b>byes to the top-seeded (strongest) teams</b> in round one.
 *
 * <p>Team power = sum of top-11 player ratings (raw, no morale/fitness), same as
 * the league test. Matches use {@code MatchSimulationService.calculateScores}.
 *
 * <h2>How to run</h2>
 * <pre>
 *   # 8-team cup (power of 2, no byes)
 *   mvn verify -Ptune -Dit.test=CupOutcomeIT#simulateCupAndReport -Dteam.ids=1,5,8,12,25,50,80,100
 *
 *   # 6-team cup (2 byes to the strongest seeds)
 *   mvn verify -Ptune -Dit.test=CupOutcomeIT#simulateCupAndReport -Dteam.ids=1,5,8,12,25,50
 * </pre>
 *
 * <p>Output: {@code target/cup-outcome-custom-{ids}.md}.
 *
 * <p>Results are <b>fully deterministic</b>: same teams + same engine config +
 * same {@code BASE_SEED} → identical numbers across runs (teams sorted by ID,
 * two seeded RNG streams). Gated behind {@code mvn verify -Ptune}.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Cup outcome — N knockout tournaments, title/final/semi probability per team")
class CupOutcomeIT {

    private static final int TOURNAMENTS = 100000;
    private static final long BASE_SEED = 20260528L;

    /** Comma-separated team IDs for the cup. Test is skipped if absent. */
    private static final String TEAM_IDS_PROPERTY = "team.ids";
    /** {@code -Dleg.format=single} (default) or {@code two-leg} for home-and-away ties. */
    private static final String LEG_FORMAT_PROPERTY = "leg.format";

    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private TournamentEngine engine;
    @Autowired private OutcomeTestSupport support;

    /** Leg format for every tie in the bracket; resolved from {@code -Dleg.format}. */
    private LegFormat legFormat = LegFormat.SINGLE_LEG;

    /**
     * Simulate a knockout cup of user-supplied teams. Skipped unless
     * {@code -Dteam.ids=ID1,ID2,...} is provided.
     *
     * <p>Constraints (fail-fast): even count, at least 2 teams, every ID must
     * exist in the team repository.
     */
    @Test
    @DisplayName("Simulate custom knockout cup — supply via -Dteam.ids=ID1,ID2,...")
    void simulateCupAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        legFormat = BracketUtil.parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(idsProperty);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException(
                    "Need at least 2 teams; got " + teamIds.size() + " (input: " + idsProperty + ")");
        }
        if (teamIds.size() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Team count must be even. Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }

        List<TeamSetup> teams = support.loadTeamsByIds(teamIds);

        AggregatedCup agg = runAggregateCup(teams);

        String idsLabel = teams.stream()
                .map(t -> String.valueOf(t.id()))
                .collect(java.util.stream.Collectors.joining(", "));
        String idsForFilename = teams.stream()
                .map(t -> String.valueOf(t.id()))
                .collect(java.util.stream.Collectors.joining("_"));
        Path reportPath = Path.of("target", "cup-outcome-custom-" + idsForFilename + ".md");

        String md = buildReport(
                "Cup: CUSTOM knockout of " + teams.size() + " teams (IDs: " + idsLabel + ")",
                teams, agg, legFormat);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    // ==================== TOURNAMENT SIMULATION ====================

    /**
     * Run {@link #TOURNAMENTS} knockout cups. Seeded RNG for scores is applied
     * at start and restored on exit so other tests are unaffected; a separate
     * seeded RNG drives the draw + AET tiebreaks.
     */
    private AggregatedCup runAggregateCup(List<TeamSetup> teams) {
        int n = teams.size();
        int nextPow2 = BracketUtil.nextPowerOfTwo(n);
        int[] stageSizes = BracketUtil.stageSizes(nextPow2); // [nextPow2, nextPow2/2, ..., 2]
        double[] powers = new double[n];
        for (int i = 0; i < n; i++) powers[i] = teams.get(i).power();

        int[] titles = new int[n];
        long[] matchesPlayed = new long[n];
        long[] matchesWon = new long[n];
        // reachedAtLeast[team][stageIdx] = # tournaments team was alive in a round
        // of that bracket size (or deeper). stageIdx maps to stageSizes order.
        long[][] reachedAtLeast = new long[n][stageSizes.length];

        Random drawRng = new Random(BASE_SEED + 1);
        matchSim.setRandomForTesting(new Random(BASE_SEED));
        long t0 = System.nanoTime();
        try {
            for (int c = 0; c < TOURNAMENTS; c++) {
                CupOutcome outcome = runOneCup(teams, powers, drawRng);
                titles[outcome.champion]++;
                for (int t = 0; t < n; t++) {
                    matchesPlayed[t] += outcome.matchesPlayed[t];
                    matchesWon[t] += outcome.matchesWon[t];
                    // stageReached is the smallest bracket-size the team played in.
                    // It "reached" every stage whose size is >= stageReached.
                    for (int si = 0; si < stageSizes.length; si++) {
                        if (outcome.stageReached[t] <= stageSizes[si]) {
                            reachedAtLeast[t][si]++;
                        }
                    }
                }
            }
        } finally {
            matchSim.setRandomForTesting(new Random());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        return new AggregatedCup(nextPow2, stageSizes, titles, matchesPlayed,
                matchesWon, reachedAtLeast, elapsedMs,
                engineConfig.getKnockout().getExtraTimeExpectedGoals(),
                engineConfig.getKnockout().getPenaltyWeakerTeamWinChance());
    }

    /**
     * One single-elimination cup via the shared {@link TournamentEngine}. Teams are
     * seeded by power (then name, the test's tiebreak) so the strongest get the
     * round-one byes; the engine plays the bracket and resolves level ties.
     */
    private CupOutcome runOneCup(List<TeamSetup> teams, double[] powers, Random drawRng) {
        List<Integer> seeded = new ArrayList<>(java.util.Arrays.asList(BracketUtil.seedByPower(teams)));
        TournamentEngine.CupResult r = engine.runCupWithByes(seeded, powers, legFormat, drawRng);
        return new CupOutcome(r.championIdx(), r.matchesPlayed(), r.matchesWon(), r.stageReached());
    }

    // ==================== REPORT ====================

    private static String buildReport(String cupLine, List<TeamSetup> teams, AggregatedCup agg, LegFormat legFormat) {
        int n = teams.size();
        int[] stageSizes = agg.stageSizes();
        int[] titles = agg.titles();
        long[][] reachedAtLeast = agg.reachedAtLeast();
        long[] matchesPlayed = agg.matchesPlayed();
        long[] matchesWon = agg.matchesWon();

        // Stage index for Final (size 2) and Semifinal (size 4), if present.
        int finalIdx = indexOfSize(stageSizes, 2);
        int semiIdx = indexOfSize(stageSizes, 4);
        int qfIdx = indexOfSize(stageSizes, 8);

        // Order teams by titles desc, then finals reached, then power.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            if (titles[a] != titles[b]) return titles[b] - titles[a];
            long fa = finalIdx >= 0 ? reachedAtLeast[a][finalIdx] : 0;
            long fb = finalIdx >= 0 ? reachedAtLeast[b][finalIdx] : 0;
            if (fa != fb) return Long.compare(fb, fa);
            return Double.compare(teams.get(b).power(), teams.get(a).power());
        });

        StringBuilder sb = new StringBuilder();
        sb.append("# Cup Outcome Simulation\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append(cupLine).append('\n');
        sb.append("Format: single-elimination, ")
          .append(legFormat == LegFormat.TWO_LEG ? "two-leg (home-and-away)" : "single-leg")
          .append(", level ties decided by extra time then penalties\n");
        sb.append("Tournaments simulated: ").append(TOURNAMENTS).append('\n');
        sb.append("Teams: ").append(n).append(" (bracket size ").append(agg.nextPow2());
        if (agg.nextPow2() > n) sb.append(", ").append(agg.nextPow2() - n).append(" bye(s) to top seeds");
        sb.append(")\n");
        sb.append("Elapsed: ").append(agg.elapsedMs()).append(" ms\n");
        sb.append("Seed: ").append(BASE_SEED).append(" (deterministic — same seed → same numbers)\n\n");

        // ---- Main table ----
        sb.append("## Results After ").append(TOURNAMENTS).append(" Tournaments\n\n");
        sb.append("Sorted by titles won. ");
        sb.append(String.format("Tiebreak: extra time (~%.1f goals) then penalties (weaker team %.0f%%).%n%n",
                agg.etGoals(), agg.penWeakerChance() * 100));

        List<String> headers = new ArrayList<>(List.of("Rank", "Team", "Power", "Titles"));
        List<MarkdownTable.Align> aligns = new ArrayList<>(List.of(
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        if (finalIdx >= 0) { headers.add("Final %"); aligns.add(MarkdownTable.Align.RIGHT); }
        if (semiIdx >= 0) { headers.add("Semi %"); aligns.add(MarkdownTable.Align.RIGHT); }
        if (qfIdx >= 0) { headers.add("QF %"); aligns.add(MarkdownTable.Align.RIGHT); }
        headers.add("Avg won");
        aligns.add(MarkdownTable.Align.RIGHT);
        headers.add("Avg played");
        aligns.add(MarkdownTable.Align.RIGHT);

        MarkdownTable main = new MarkdownTable(headers, aligns);
        int rank = 1;
        for (int t : order) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(rank++));
            row.add(teams.get(t).name());
            row.add(String.format("%.0f", teams.get(t).power()));
            row.add(String.format("%.1f%%", titles[t] * 100.0 / TOURNAMENTS));
            if (finalIdx >= 0) row.add(pct(reachedAtLeast[t][finalIdx]));
            if (semiIdx >= 0) row.add(pct(reachedAtLeast[t][semiIdx]));
            if (qfIdx >= 0) row.add(pct(reachedAtLeast[t][qfIdx]));
            row.add(String.format("%.2f", matchesWon[t] / (double) TOURNAMENTS));
            row.add(String.format("%.2f", matchesPlayed[t] / (double) TOURNAMENTS));
            main.addRow(row.toArray(new String[0]));
        }
        sb.append(main.render()).append('\n');

        // ---- Stage-reached heatmap ----
        sb.append("## Stage Reached (% of tournaments each team reached at least this stage)\n\n");
        sb.append("Columns from opening round to the trophy. ");
        sb.append("\"Winner\" = won the final.\n\n");
        List<String> heatHeaders = new ArrayList<>();
        List<MarkdownTable.Align> heatAligns = new ArrayList<>();
        heatHeaders.add("Team");
        heatAligns.add(MarkdownTable.Align.LEFT);
        for (int size : stageSizes) {
            heatHeaders.add(BracketUtil.stageLabel(size));
            heatAligns.add(MarkdownTable.Align.RIGHT);
        }
        heatHeaders.add("Winner");
        heatAligns.add(MarkdownTable.Align.RIGHT);
        MarkdownTable heat = new MarkdownTable(heatHeaders, heatAligns);
        for (int t : order) {
            String[] row = new String[stageSizes.length + 2];
            row[0] = teams.get(t).name();
            for (int si = 0; si < stageSizes.length; si++) {
                row[si + 1] = pct(reachedAtLeast[t][si]);
            }
            row[stageSizes.length + 1] = String.format("%.1f%%", titles[t] * 100.0 / TOURNAMENTS);
            heat.addRow(row);
        }
        sb.append(heat.render()).append('\n');

        sb.append("## How to read this report\n\n");
        sb.append("- **Power** = sum of top-11 player ratings (no morale/fitness adjustments).\n");
        sb.append("- **Titles** = % of tournaments this team won the cup. Sums to 100% across all teams.\n");
        sb.append("- **Final / Semi / QF %** = how often the team reached at least that stage.\n");
        sb.append("- **Avg won / played** = mean matches won / played per tournament (byes don't count as matches).\n");
        sb.append("- A knockout cup is far noisier than a league: a single bad match ends a run, ");
        sb.append("so even the strongest team's title share is much lower than its league win share.\n");

        return sb.toString();
    }

    private static String pct(long count) {
        return String.format("%.1f%%", count * 100.0 / TOURNAMENTS);
    }

    private static int indexOfSize(int[] sizes, int target) {
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == target) return i;
        return -1;
    }

    // ==================== INNER TYPES ====================

    private record CupOutcome(int champion, int[] matchesPlayed, int[] matchesWon, int[] stageReached) {}

    private record AggregatedCup(int nextPow2, int[] stageSizes, int[] titles,
                                 long[] matchesPlayed, long[] matchesWon,
                                 long[][] reachedAtLeast, long elapsedMs,
                                 double etGoals, double penWeakerChance) {}

}
