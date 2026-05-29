package com.footballmanagergamesimulator.integration.europe;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.EuropeanFormatPlan;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.repository.TeamRepository;
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

/**
 * Long-run League of Champions (LoC) outcome simulation: given any number of
 * teams ≥ 16 (by ID), runs the full LoC format and reports how often each team
 * wins the trophy, reaches each knockout stage, and qualifies from its group.
 *
 * <p>Format (general, mirrors the real engine and scales with the field size):
 * <ul>
 *   <li><b>Preliminary rounds</b> — only when there are more than 16 teams. The
 *       weakest-seeded teams play single-leg knockout rounds while the strongest
 *       get byes, until exactly 16 teams remain. With 16 teams there are none.</li>
 *   <li><b>Group stage</b> — the 16 survivors are pot-seeded into 4 groups of 4
 *       (one team per pot per group). Each group plays a double round-robin over
 *       6 matchdays (8 matches per matchday across the 4 groups). Standings:
 *       points → goal difference → goals for.</li>
 *   <li><b>Knockout</b> — top 2 of each group (8 teams) play a single-leg
 *       bracket (QF → SF → Final). Draws are decided by the engine's
 *       power-weighted AET tiebreak ({@link MatchEngineConfig.Knockout}).</li>
 * </ul>
 *
 * <p>For the <b>first edition only</b>, a full phase-by-phase match log is
 * printed (every preliminary round, every group matchday, and every knockout
 * round, with the actual fixtures and scores). The remaining editions feed the
 * aggregate probability tables.
 *
 * <h2>How to run</h2>
 * <pre>
 *   # canonical 16-team LoC (no preliminaries — straight to 4 groups of 4)
 *   mvn verify -Ptune -Dit.test=LeagueOfChampionsOutcomeIT#simulateLeagueOfChampionsAndReport \
 *       -Dteam.ids=1,5,8,12,25,50,80,100,2,6,9,13,26,51,81,101
 *
 *   # 20 teams (4 preliminary matches trim the field to 16)
 *   mvn verify -Ptune -Dit.test=LeagueOfChampionsOutcomeIT#simulateLeagueOfChampionsAndReport \
 *       -Dteam.ids=1,5,8,12,25,50,80,100,2,6,9,13,26,51,81,101,3,7,10,14
 * </pre>
 *
 * <p>Output: {@code target/loc-outcome-custom-{count}teams.md}. Fully
 * deterministic (seeded draw + score RNG + {@code bootstrap.seed}). Gated behind
 * {@code mvn verify -Ptune}.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("League of Champions outcome — preliminaries + group stage + knockout, trophy probability per team")
class LeagueOfChampionsOutcomeIT {

    private static final int TOURNAMENTS = 1000;
    private static final long BASE_SEED = 20260528L;

    // Format shape — read from the PRODUCTION CompetitionFormat (typeId 4) so the
    // outcome simulation and the live game share a single source of truth. Set in
    // the test method from the configured LoC format; do not hardcode here.
    private int groupSlots;       // teams entering the group stage (groupCount * groupSize)
    private int groupCount;
    private int groupSize;
    private int qualifyPerGroup;
    private int koBracket;        // knockout entrants (groupCount * qualifyPerGroup)

    private static final String TEAM_IDS_PROPERTY = "team.ids";
    /** {@code -Dleg.format=single} (default) or {@code two-leg} for home-and-away knockout ties. */
    private static final String LEG_FORMAT_PROPERTY = "leg.format";

    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private TournamentEngine engine;
    @Autowired private OutcomeTestSupport support;
    @Autowired private CompetitionFormatConfig competitionFormat;

    /** Leg format for knockout ties (groups are always single matches); from {@code -Dleg.format}. */
    private LegFormat legFormat = LegFormat.SINGLE_LEG;

    /**
     * Simulate a League of Champions of user-supplied teams. Skipped unless
     * {@code -Dteam.ids=ID1,ID2,...} is provided. Requires at least 16 teams.
     */
    @Test
    @DisplayName("Simulate custom League of Champions — supply via -Dteam.ids=ID1,ID2,...")
    void simulateLeagueOfChampionsAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        legFormat = BracketUtil.parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        // Single source of truth: take the LoC shape from the production format.
        CompetitionFormat fmt = competitionFormat.get(4);
        groupCount = fmt.groupCount();
        groupSize = fmt.groupSize();
        qualifyPerGroup = fmt.qualifyPerGroupToKnockout();
        groupSlots = groupCount * groupSize;
        koBracket = groupCount * qualifyPerGroup;

        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(idsProperty);
        if (teamIds.size() < groupSlots) {
            throw new IllegalArgumentException(
                    "Need at least " + groupSlots + " teams (the group stage has "
                            + groupSlots + "). Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }
        // Validate the shape is a clean tournament (same rule as production).
        EuropeanFormatPlan.derive(teamIds.size(), groupCount, groupSize, qualifyPerGroup);

        List<TeamSetup> teams = support.loadTeamsByIds(teamIds);

        StringBuilder firstEditionLog = new StringBuilder();
        AggregatedLoc agg = runAggregateLoc(teams, firstEditionLog);

        Path reportPath = Path.of("target", "loc-outcome-custom-" + teams.size() + "teams.md");
        String md = buildReport(teams, agg, firstEditionLog.toString(), legFormat);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    // ==================== TOURNAMENT SIMULATION ====================

    private AggregatedLoc runAggregateLoc(List<TeamSetup> teams, StringBuilder firstEditionLog) {
        int n = teams.size();
        int[] koStageSizes = BracketUtil.stageSizes(koBracket); // [8, 4, 2]
        double[] powers = new double[n];
        for (int i = 0; i < n; i++) powers[i] = teams.get(i).power();

        int[] titles = new int[n];
        long[] reachedGroup = new long[n];
        long[] qualified = new long[n];
        long[] groupPointsTotal = new long[n];
        long[] groupPosTotal = new long[n];
        long[] koMatchesWon = new long[n];
        long[][] koReachedAtLeast = new long[n][koStageSizes.length];

        Random drawRng = new Random(BASE_SEED + 1);
        matchSim.setRandomForTesting(new Random(BASE_SEED));
        long t0 = System.nanoTime();
        try {
            for (int c = 0; c < TOURNAMENTS; c++) {
                StringBuilder log = (c == 0) ? firstEditionLog : null;
                LocOutcome o = runOneTournament(teams, powers, drawRng, log);
                titles[o.champion]++;
                for (int t = 0; t < n; t++) {
                    if (o.reachedGroup[t]) {
                        reachedGroup[t]++;
                        groupPointsTotal[t] += o.groupPoints[t];
                        groupPosTotal[t] += o.groupPosition[t];
                    }
                    if (o.qualified[t]) qualified[t]++;
                    koMatchesWon[t] += o.koMatchesWon[t];
                    for (int si = 0; si < koStageSizes.length; si++) {
                        if (o.koStageReached[t] <= koStageSizes[si]) koReachedAtLeast[t][si]++;
                    }
                }
            }
        } finally {
            matchSim.setRandomForTesting(new Random());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        return new AggregatedLoc(koStageSizes, titles, reachedGroup, qualified, groupPointsTotal,
                groupPosTotal, koMatchesWon, koReachedAtLeast, elapsedMs,
                engineConfig.getKnockout().getExtraTimeExpectedGoals(),
                engineConfig.getKnockout().getPenaltyWeakerTeamWinChance());
    }

    /**
     * One LoC edition: preliminaries (if N&gt;16) → group stage → knockout, all
     * via the shared {@link TournamentEngine}. This method only aggregates the
     * per-team statistics and (for the first edition) renders the phase log.
     */
    private LocOutcome runOneTournament(List<TeamSetup> teams, double[] powers, Random drawRng, StringBuilder log) {
        int n = teams.size();
        boolean[] reachedGroup = new boolean[n];
        int[] groupPoints = new int[n];
        int[] groupPosition = new int[n];
        boolean[] qualified = new boolean[n];
        int[] koMatchesWon = new int[n];
        int[] koStageReached = new int[n];
        java.util.Arrays.fill(koStageReached, Integer.MAX_VALUE);

        // ---- 1. Preliminaries: trim the field down to exactly 16 ----
        List<Integer> allTeams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) allTeams.add(i);
        TournamentEngine.PrelimResult prelim = engine.trimToSize(allTeams, powers, groupSlots, legFormat, drawRng);
        if (log != null) {
            int prelimRound = 1;
            for (TournamentEngine.PrelimRound round : prelim.rounds()) {
                log.append("### Preliminary Round ").append(prelimRound++)
                   .append(" — ").append(round.ties().size()).append(" match(es), ")
                   .append(round.byes().size()).append(" bye(s)\n\n");
                for (TournamentEngine.TieOutcome t : round.ties()) log.append(formatKoMatch(teams, t)).append('\n');
                if (!round.byes().isEmpty()) log.append("- _Byes:_ ").append(namesOf(teams, round.byes())).append('\n');
                log.append('\n');
            }
        }
        List<Integer> groupTeams = prelim.survivors(); // exactly 16
        for (int t : groupTeams) reachedGroup[t] = true;

        // ---- 2. Group draw (pot-seeded, one team per pot per group) ----
        List<List<Integer>> groups = engine.potSeededGroups(groupTeams, powers, groupCount, groupSize, drawRng);
        if (log != null) {
            log.append("## Group Stage Draw\n\n");
            for (int g = 0; g < groups.size(); g++) {
                log.append("- **Group ").append((char) ('A' + g)).append("**: ")
                   .append(namesOf(teams, groups.get(g))).append('\n');
            }
            log.append('\n');
        }

        // ---- 3. Group stage (6 matchdays) + qualification ----
        List<TournamentEngine.GroupResult> groupResults = engine.playGroups(groups, powers, BracketUtil.GROUP_SCHEDULE);
        if (log != null) {
            for (int md = 0; md < BracketUtil.GROUP_SCHEDULE.length; md++) {
                log.append("### Group Stage — Matchday ").append(md + 1).append("\n\n");
                for (TournamentEngine.GroupResult gr : groupResults) {
                    for (TournamentEngine.GroupMatch m : gr.matchdays().get(md)) {
                        log.append("- [Group ").append((char) ('A' + gr.groupIndex())).append("] ")
                           .append(formatGroupMatch(teams, m.homeIdx(), m.awayIdx(), m.homeGoals(), m.awayGoals())).append('\n');
                    }
                }
                log.append('\n');
            }
        }
        List<Integer> qualifiers = new ArrayList<>(groupCount * qualifyPerGroup);
        for (TournamentEngine.GroupResult gr : groupResults) {
            if (log != null) log.append("**Group ").append((char) ('A' + gr.groupIndex())).append(" final standings:**\n\n");
            for (int pos = 0; pos < gr.teams().size(); pos++) {
                int teamIdx = gr.teamAtPosition(pos);
                groupPoints[teamIdx] = gr.pointsAtPosition(pos);
                groupPosition[teamIdx] = pos + 1;
                boolean adv = pos < qualifyPerGroup;
                if (adv) { qualified[teamIdx] = true; qualifiers.add(teamIdx); }
                if (log != null) {
                    log.append("  ").append(pos + 1).append(". ").append(teams.get(teamIdx).name())
                       .append(" — ").append(gr.pointsAtPosition(pos)).append(" pts (")
                       .append(gr.goalsForAtPosition(pos)).append('-').append(gr.goalsAgainstAtPosition(pos)).append(')')
                       .append(adv ? "  ✅ qualifies" : "").append('\n');
                }
            }
            if (log != null) log.append('\n');
        }

        // ---- 4. Knockout ----
        TournamentEngine.KnockoutResult ko = engine.runKnockout(qualifiers, powers, legFormat, drawRng);
        if (!ko.rounds().isEmpty()) {
            int firstBracket = ko.rounds().get(0).bracketSize();
            for (int t : qualifiers) koStageReached[t] = Math.min(koStageReached[t], firstBracket);
        }
        for (TournamentEngine.KnockoutRound round : ko.rounds()) {
            if (log != null) log.append("### ").append(BracketUtil.stageLabel(round.bracketSize())).append("\n\n");
            for (TournamentEngine.TieOutcome t : round.ties()) {
                koMatchesWon[t.winnerIdx()]++;
                koStageReached[t.winnerIdx()] = Math.min(koStageReached[t.winnerIdx()], round.bracketSize() / 2);
                if (log != null) log.append(formatKoMatch(teams, t)).append('\n');
            }
            if (log != null) log.append('\n');
        }
        int champion = ko.championIdx();
        if (log != null && champion >= 0) {
            log.append("## 🏆 Champion: ").append(teams.get(champion).name()).append("\n\n");
        }

        return new LocOutcome(champion, reachedGroup, groupPoints, groupPosition, qualified,
                koMatchesWon, koStageReached);
    }

    // ==================== LOG FORMATTING ====================

    private static String formatKoMatch(List<TeamSetup> teams, TournamentEngine.TieOutcome t) {
        return "- " + teams.get(t.aIdx()).name() + " vs " + teams.get(t.bIdx()).name()
                + " — " + t.tie().summary()
                + "  → **" + teams.get(t.winnerIdx()).name() + "** advances";
    }

    private static String formatGroupMatch(List<TeamSetup> teams, int homeIdx, int awayIdx, int sH, int sA) {
        return teams.get(homeIdx).name() + " " + sH + "–" + sA + " " + teams.get(awayIdx).name();
    }

    private static String namesOf(List<TeamSetup> teams, List<Integer> idxs) {
        return idxs.stream().map(i -> teams.get(i).name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ==================== REPORT ====================

    private String buildReport(List<TeamSetup> teams, AggregatedLoc agg, String firstEditionLog, LegFormat legFormat) {
        int n = teams.size();
        int[] koStageSizes = agg.koStageSizes();
        int[] titles = agg.titles();
        long[][] koReachedAtLeast = agg.koReachedAtLeast();
        long[] reachedGroup = agg.reachedGroup();
        long[] qualified = agg.qualified();
        long[] groupPointsTotal = agg.groupPointsTotal();
        long[] groupPosTotal = agg.groupPosTotal();
        long[] koMatchesWon = agg.koMatchesWon();

        int finalIdx = indexOfSize(koStageSizes, 2);
        int semiIdx = indexOfSize(koStageSizes, 4);
        int qfIdx = indexOfSize(koStageSizes, 8);

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            if (titles[a] != titles[b]) return titles[b] - titles[a];
            long fa = finalIdx >= 0 ? koReachedAtLeast[a][finalIdx] : 0;
            long fb = finalIdx >= 0 ? koReachedAtLeast[b][finalIdx] : 0;
            if (fa != fb) return Long.compare(fb, fa);
            return Double.compare(teams.get(b).power(), teams.get(a).power());
        });

        StringBuilder sb = new StringBuilder();
        sb.append("# League of Champions Outcome Simulation\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Format: ");
        if (n > groupSlots) sb.append("preliminary rounds trim ").append(n).append(" → ").append(groupSlots).append(" teams, then ");
        sb.append(groupCount).append(" groups of ").append(groupSize)
                .append(" (double round-robin, 6 matchdays), top ").append(qualifyPerGroup)
                .append(" advance → ").append(koBracket).append("-team ")
                .append(legFormat == LegFormat.TWO_LEG ? "two-leg (home-and-away)" : "single-leg")
                .append(" knockout (level ties → extra time → penalties)\n");
        sb.append("Teams: ").append(n).append('\n');
        sb.append("Editions simulated: ").append(TOURNAMENTS).append('\n');
        sb.append("Elapsed: ").append(agg.elapsedMs()).append(" ms\n");
        sb.append(String.format("Knockout tiebreak: extra time (~%.1f goals) then penalties (weaker team %.0f%%)%n",
                agg.etGoals(), agg.penWeakerChance() * 100));
        sb.append("Seed: ").append(BASE_SEED).append(" (deterministic — same seed → same numbers)\n\n");

        // ---- Main table ----
        sb.append("## Results After ").append(TOURNAMENTS).append(" Editions\n\n");
        sb.append("Sorted by trophies won. \"Reach grp\" = reached the group stage; ");
        sb.append("\"Qualify\" = finished top ").append(qualifyPerGroup).append(" in the group.\n\n");
        List<String> headers = new ArrayList<>(List.of(
                "Rank", "Team", "Power", "Trophies", "Reach grp", "Qualify", "Avg grp pos", "Avg grp pts"));
        List<MarkdownTable.Align> aligns = new ArrayList<>(List.of(
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        if (finalIdx >= 0) { headers.add("Final %"); aligns.add(MarkdownTable.Align.RIGHT); }
        if (semiIdx >= 0) { headers.add("Semi %"); aligns.add(MarkdownTable.Align.RIGHT); }
        if (qfIdx >= 0) { headers.add("QF %"); aligns.add(MarkdownTable.Align.RIGHT); }
        headers.add("KO won");
        aligns.add(MarkdownTable.Align.RIGHT);

        MarkdownTable main = new MarkdownTable(headers, aligns);
        int rank = 1;
        for (int t : order) {
            long rg = reachedGroup[t];
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(rank++));
            row.add(teams.get(t).name());
            row.add(String.format("%.0f", teams.get(t).power()));
            row.add(pct(titles[t]));
            row.add(pct(rg));
            row.add(pct(qualified[t]));
            row.add(rg == 0 ? "—" : String.format("%.2f", groupPosTotal[t] / (double) rg));
            row.add(rg == 0 ? "—" : String.format("%.1f", groupPointsTotal[t] / (double) rg));
            if (finalIdx >= 0) row.add(pct(koReachedAtLeast[t][finalIdx]));
            if (semiIdx >= 0) row.add(pct(koReachedAtLeast[t][semiIdx]));
            if (qfIdx >= 0) row.add(pct(koReachedAtLeast[t][qfIdx]));
            row.add(String.format("%.2f", koMatchesWon[t] / (double) TOURNAMENTS));
            main.addRow(row.toArray(new String[0]));
        }
        sb.append(main.render()).append('\n');

        // ---- Knockout stage heatmap ----
        sb.append("## Knockout Stage Reached (% of editions reaching at least this stage)\n\n");
        sb.append("Only group qualifiers enter the knockout. \"Winner\" = won the final.\n\n");
        List<String> heatHeaders = new ArrayList<>();
        List<MarkdownTable.Align> heatAligns = new ArrayList<>();
        heatHeaders.add("Team");
        heatAligns.add(MarkdownTable.Align.LEFT);
        for (int size : koStageSizes) {
            heatHeaders.add(BracketUtil.stageLabel(size));
            heatAligns.add(MarkdownTable.Align.RIGHT);
        }
        heatHeaders.add("Winner");
        heatAligns.add(MarkdownTable.Align.RIGHT);
        MarkdownTable heat = new MarkdownTable(heatHeaders, heatAligns);
        for (int t : order) {
            String[] row = new String[koStageSizes.length + 2];
            row[0] = teams.get(t).name();
            for (int si = 0; si < koStageSizes.length; si++) row[si + 1] = pct(koReachedAtLeast[t][si]);
            row[koStageSizes.length + 1] = pct(titles[t]);
            heat.addRow(row);
        }
        sb.append(heat.render()).append('\n');

        // ---- First-edition phase-by-phase log ----
        sb.append("# First Edition — Phase by Phase\n\n");
        sb.append("Every match of the first simulated edition, in order.\n\n");
        sb.append(firstEditionLog);

        sb.append("## How to read this report\n\n");
        sb.append("- **Power** = sum of top-11 player ratings (no morale/fitness adjustments).\n");
        sb.append("- **Trophies** = % of editions this team won the LoC. Sums to 100% across all teams.\n");
        sb.append("- **Reach grp** = how often the team survived the preliminaries into the group stage ");
        sb.append("(100% when there are no preliminaries, i.e. exactly 16 teams).\n");
        sb.append("- **Qualify** = how often it finished top ").append(qualifyPerGroup)
                .append(" in its group. **Avg grp pos/pts** are averaged over editions it reached the group stage.\n");
        sb.append("- **Final / Semi / QF %** = how often the team reached at least that knockout stage.\n");

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

    private record LocOutcome(int champion, boolean[] reachedGroup, int[] groupPoints,
                              int[] groupPosition, boolean[] qualified,
                              int[] koMatchesWon, int[] koStageReached) {}

    private record AggregatedLoc(int[] koStageSizes, int[] titles, long[] reachedGroup, long[] qualified,
                                 long[] groupPointsTotal, long[] groupPosTotal, long[] koMatchesWon,
                                 long[][] koReachedAtLeast, long elapsedMs,
                                 double etGoals, double penWeakerChance) {}

}
