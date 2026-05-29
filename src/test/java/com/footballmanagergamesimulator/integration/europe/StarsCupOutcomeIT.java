package com.footballmanagergamesimulator.integration.europe;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
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
 * Long-run Stars Cup (SC) outcome simulation: the third European format, after
 * {@link LeagueOfChampionsOutcomeIT} and
 * {@link com.footballmanagergamesimulator.integration.cup.CupOutcomeIT}.
 *
 * <p>Format mirrors the real engine ({@code EuropeanCompetitionService}: group
 * winners → QF, runners-up → playoff). Because the live Stars Cup is fed by LoC
 * drop-outs (group 3rd places + knockout losers), a standalone simulation closes
 * the bracket self-contained:
 * <ul>
 *   <li><b>Preliminary rounds</b> — only when there are more than 16 teams;
 *       weakest seeds play single-leg knockouts (strongest get byes) until 16
 *       remain (same trimming as the LoC test).</li>
 *   <li><b>Group stage</b> — 16 teams in 4 groups of 4, double round-robin over
 *       6 matchdays. Standings: points → goal difference → goals for.</li>
 *   <li><b>Group winners (4)</b> go straight to the quarterfinals.</li>
 *   <li><b>Group 2nd + 3rd (8)</b> contest a <b>playoff</b> (round 7) for the
 *       remaining 4 quarterfinal slots. 4th place is eliminated.</li>
 *   <li><b>Knockout</b> — 8 teams (4 group winners + 4 playoff winners) play
 *       QF → SF → Final.</li>
 * </ul>
 *
 * <p>All knockout ties (preliminaries, playoff, QF/SF/Final) run through the
 * shared TournamentEngine / KnockoutTieResolver: level ties go to extra time then penalties.
 * Pass {@code -Dleg.format=two-leg} to make every tie home-and-away (the final
 * stays the format you pick — like the other tests, the whole bracket uses one
 * format).
 *
 * <h2>How to run</h2>
 * <pre>
 *   # 16-team Stars Cup, single-leg knockout
 *   mvn verify -Ptune -Dit.test=StarsCupOutcomeIT#simulateStarsCupAndReport \
 *       -Dteam.ids=1,5,8,12,25,50,80,100,2,6,9,13,26,51,81,101
 *
 *   # same field, two-leg ties
 *   mvn verify -Ptune -Dit.test=StarsCupOutcomeIT#simulateStarsCupAndReport \
 *       -Dteam.ids=1,5,8,12,25,50,80,100,2,6,9,13,26,51,81,101 -Dleg.format=two-leg
 * </pre>
 *
 * <p>Output: {@code target/stars-cup-outcome-custom-{count}teams.md}. Fully
 * deterministic (seeded draw + score RNG + {@code bootstrap.seed}). Gated behind
 * {@code mvn verify -Ptune}.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Stars Cup outcome — groups + playoff + knockout, trophy probability per team")
class StarsCupOutcomeIT {

    private static final int TOURNAMENTS = 1000;
    private static final long BASE_SEED = 20260528L;

    // Format shape — read from the PRODUCTION CompetitionFormat (typeId 5) so the
    // outcome simulation and the live game share one source of truth. Set in the
    // test method; the knockout bracket = group winners + playoff winners (the
    // Stars-Cup-specific playoff injects the group runners-up).
    private int groupSlots;   // group-stage size (groupCount * groupSize)
    private int groupCount;
    private int groupSize;
    private int koBracket;    // 2 * groupCount (group winners + playoff winners)

    private static final String TEAM_IDS_PROPERTY = "team.ids";
    private static final String LEG_FORMAT_PROPERTY = "leg.format";
    private static final String LOC_DROPOUT_IDS_PROPERTY = "loc.dropout.ids";

    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private TournamentEngine engine;
    @Autowired private OutcomeTestSupport support;
    @Autowired private CompetitionFormatConfig competitionFormat;

    private LegFormat legFormat = LegFormat.SINGLE_LEG;

    @Test
    @DisplayName("Simulate custom Stars Cup — supply via -Dteam.ids=ID1,ID2,...")
    void simulateStarsCupAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        legFormat = BracketUtil.parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        // Single source of truth: take the Stars Cup group shape from the production format.
        CompetitionFormat fmt = competitionFormat.get(5);
        groupCount = fmt.groupCount();
        groupSize = fmt.groupSize();
        groupSlots = groupCount * groupSize;
        koBracket = 2 * groupCount; // group winners + playoff winners

        List<Long> teamIds = OutcomeTestSupport.parseTeamIds(idsProperty);
        if (teamIds.size() < groupSlots) {
            throw new IllegalArgumentException(
                    "Need at least " + groupSlots + " teams (the group stage has "
                            + groupSlots + "). Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }

        List<TeamSetup> teams = support.loadTeamsByIds(teamIds);

        StringBuilder firstEditionLog = new StringBuilder();
        Aggregated agg = runAggregate(teams, firstEditionLog);

        Path reportPath = Path.of("target", "stars-cup-outcome-custom-" + teams.size() + "teams.md");
        String md = buildReport(teams, agg, firstEditionLog.toString(), legFormat);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    @Test
    @DisplayName("Simulate Stars Cup with LoC drop-outs in the playoff — -Dteam.ids=... -Dloc.dropout.ids=...")
    void simulateStarsCupWithLocDropoutsAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        String dropoutProperty = System.getProperty(LOC_DROPOUT_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank()
                        && dropoutProperty != null && !dropoutProperty.isBlank(),
                "Skipping — supply -Dteam.ids=... (group field) and -Dloc.dropout.ids=... (playoff entrants)");

        legFormat = BracketUtil.parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        CompetitionFormat fmt = competitionFormat.get(5);
        groupCount = fmt.groupCount();
        groupSize = fmt.groupSize();
        groupSlots = groupCount * groupSize;
        koBracket = 2 * groupCount; // group winners + playoff winners

        List<Long> scIds = OutcomeTestSupport.parseTeamIds(idsProperty);
        List<Long> dropoutIds = OutcomeTestSupport.parseTeamIds(dropoutProperty);
        if (scIds.size() < groupSlots) {
            throw new IllegalArgumentException("Need at least " + groupSlots
                    + " group teams (-Dteam.ids). Got " + scIds.size());
        }
        // The playoff pairs each group runner-up (one per group) with one LoC drop-out,
        // so the drop-out count must equal the group count.
        if (dropoutIds.size() != groupCount) {
            throw new IllegalArgumentException("Need exactly " + groupCount
                    + " LoC drop-outs (-Dloc.dropout.ids), one per group runner-up. Got " + dropoutIds.size());
        }
        if (scIds.stream().anyMatch(dropoutIds::contains)) {
            throw new IllegalArgumentException("team.ids and loc.dropout.ids must be disjoint");
        }

        List<TeamSetup> teams = new ArrayList<>(support.loadTeamsByIds(scIds));
        int groupFieldSize = teams.size();
        teams.addAll(support.loadTeamsByIds(dropoutIds));

        List<Integer> groupField = new ArrayList<>(groupFieldSize);
        for (int i = 0; i < groupFieldSize; i++) groupField.add(i);
        List<Integer> externals = new ArrayList<>(dropoutIds.size());
        for (int i = groupFieldSize; i < teams.size(); i++) externals.add(i);

        StringBuilder firstEditionLog = new StringBuilder();
        Aggregated agg = runAggregate(teams, groupField, externals, firstEditionLog);

        Path reportPath = Path.of("target",
                "stars-cup-outcome-loc-dropouts-" + groupFieldSize + "plus" + dropoutIds.size() + ".md");
        String md = buildReport(teams, agg, firstEditionLog.toString(), legFormat);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    // ==================== TOURNAMENT SIMULATION ====================

    private Aggregated runAggregate(List<TeamSetup> teams, StringBuilder firstEditionLog) {
        // Default: all teams contest the group stage; no external playoff entrants.
        List<Integer> groupField = new ArrayList<>(teams.size());
        for (int i = 0; i < teams.size(); i++) groupField.add(i);
        return runAggregate(teams, groupField, List.of(), firstEditionLog);
    }

    /**
     * @param groupField teams (by index) that contest the group stage
     * @param externalPlayoffEntrants teams (by index) injected straight into the
     *        playoff — models the real LoC drop-outs (3rd places). When non-empty,
     *        only group RUNNERS-UP join them in the playoff (3rd places are out).
     */
    private Aggregated runAggregate(List<TeamSetup> teams, List<Integer> groupField,
                                    List<Integer> externalPlayoffEntrants, StringBuilder firstEditionLog) {
        int n = teams.size();
        int[] koStageSizes = BracketUtil.stageSizes(koBracket); // [8, 4, 2]
        double[] powers = new double[n];
        for (int i = 0; i < n; i++) powers[i] = teams.get(i).power();

        int[] titles = new int[n];
        long[] reachedGroup = new long[n];
        long[] groupWinner = new long[n];   // finished 1st in group → direct QF
        long[] reachedPlayoff = new long[n]; // finished 2nd/3rd → playoff
        long[] reachedQf = new long[n];     // entered the quarterfinals
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
                Outcome o = runOneTournament(teams, powers, groupField, externalPlayoffEntrants, drawRng, log);
                titles[o.champion]++;
                for (int t = 0; t < n; t++) {
                    if (o.reachedGroup[t]) {
                        reachedGroup[t]++;
                        groupPointsTotal[t] += o.groupPoints[t];
                        groupPosTotal[t] += o.groupPosition[t];
                    }
                    if (o.groupWinner[t]) groupWinner[t]++;
                    if (o.reachedPlayoff[t]) reachedPlayoff[t]++;
                    if (o.reachedQf[t]) reachedQf[t]++;
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

        return new Aggregated(koStageSizes, titles, reachedGroup, groupWinner, reachedPlayoff, reachedQf,
                groupPointsTotal, groupPosTotal, koMatchesWon, koReachedAtLeast, elapsedMs,
                engineConfig.getKnockout().getExtraTimeExpectedGoals(),
                engineConfig.getKnockout().getPenaltyWeakerTeamWinChance());
    }

    /**
     * One Stars Cup edition: preliminaries (if N&gt;16) → groups → playoff →
     * knockout, all via the shared {@link TournamentEngine}. This method only
     * aggregates per-team statistics and (for the first edition) renders the log.
     */
    private Outcome runOneTournament(List<TeamSetup> teams, double[] powers, List<Integer> groupField,
                                     List<Integer> externalPlayoffEntrants, Random drawRng, StringBuilder log) {
        int n = teams.size();
        boolean[] reachedGroup = new boolean[n];
        int[] groupPoints = new int[n];
        int[] groupPosition = new int[n];
        boolean[] groupWinner = new boolean[n];
        boolean[] reachedPlayoff = new boolean[n];
        boolean[] reachedQf = new boolean[n];
        int[] koMatchesWon = new int[n];
        int[] koStageReached = new int[n];
        java.util.Arrays.fill(koStageReached, Integer.MAX_VALUE);

        // ---- 1. Preliminaries: trim the group field to exactly groupSlots ----
        List<Integer> allTeams = new ArrayList<>(groupField);
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

        // ---- 2. Group draw ----
        List<List<Integer>> groups = engine.potSeededGroups(groupTeams, powers, groupCount, groupSize, drawRng);
        if (log != null) {
            log.append("## Group Stage Draw\n\n");
            for (int g = 0; g < groups.size(); g++) {
                log.append("- **Group ").append((char) ('A' + g)).append("**: ")
                   .append(namesOf(teams, groups.get(g))).append('\n');
            }
            log.append('\n');
        }

        // ---- 3. Group stage → winners (direct QF) + 2nd/3rd (playoff); 4th out ----
        List<TournamentEngine.GroupResult> groupResults = engine.playGroups(groups, powers, BracketUtil.GROUP_SCHEDULE);
        if (log != null) {
            for (int md = 0; md < BracketUtil.GROUP_SCHEDULE.length; md++) {
                log.append("### Group Stage — Matchday ").append(md + 1).append("\n\n");
                for (TournamentEngine.GroupResult gr : groupResults) {
                    for (TournamentEngine.GroupMatch m : gr.matchdays().get(md)) {
                        log.append("- [Group ").append((char) ('A' + gr.groupIndex())).append("] ")
                           .append(teams.get(m.homeIdx()).name()).append(" ").append(m.homeGoals()).append("–").append(m.awayGoals())
                           .append(" ").append(teams.get(m.awayIdx()).name()).append('\n');
                    }
                }
                log.append('\n');
            }
        }
        // With external (LoC drop-out) entrants only the runners-up join the playoff
        // (3rd places are out, as in the real game); otherwise 2nd+3rd both go through.
        int lastPlayoffPos = externalPlayoffEntrants.isEmpty() ? 2 : 1;
        List<Integer> directQf = new ArrayList<>(groupCount);
        List<Integer> playoffTeams = new ArrayList<>(groupCount * 2);
        for (TournamentEngine.GroupResult gr : groupResults) {
            if (log != null) log.append("**Group ").append((char) ('A' + gr.groupIndex())).append(" final standings:**\n\n");
            for (int pos = 0; pos < gr.teams().size(); pos++) {
                int teamIdx = gr.teamAtPosition(pos);
                groupPoints[teamIdx] = gr.pointsAtPosition(pos);
                groupPosition[teamIdx] = pos + 1;
                String tag;
                if (pos == 0) { groupWinner[teamIdx] = true; directQf.add(teamIdx); tag = "  ✅ → QF"; }
                else if (pos <= lastPlayoffPos) { playoffTeams.add(teamIdx); tag = "  ↘ playoff"; }
                else tag = "  ✗ out";
                if (log != null) {
                    log.append("  ").append(pos + 1).append(". ").append(teams.get(teamIdx).name())
                       .append(" — ").append(gr.pointsAtPosition(pos)).append(" pts (")
                       .append(gr.goalsForAtPosition(pos)).append('-').append(gr.goalsAgainstAtPosition(pos)).append(')')
                       .append(tag).append('\n');
                }
            }
            if (log != null) log.append('\n');
        }
        // Inject the external LoC drop-outs straight into the playoff.
        if (!externalPlayoffEntrants.isEmpty()) {
            if (log != null) {
                log.append("**LoC drop-outs entering the playoff:** ")
                   .append(namesOf(teams, externalPlayoffEntrants)).append("\n\n");
            }
            playoffTeams.addAll(externalPlayoffEntrants);
        }
        for (int t : playoffTeams) reachedPlayoff[t] = true;

        // ---- 4. Playoff (round 7): 8 teams → 4 QF slots ----
        if (log != null) log.append("## Playoff (for the last 4 quarterfinal places)\n\n");
        TournamentEngine.KnockoutRound playoff = engine.drawAndPlayRound(playoffTeams, powers, legFormat, drawRng);
        List<Integer> playoffWinners = new ArrayList<>(groupCount);
        for (TournamentEngine.TieOutcome t : playoff.ties()) {
            playoffWinners.add(t.winnerIdx());
            if (log != null) log.append(formatKoMatch(teams, t)).append('\n');
        }
        if (log != null) log.append('\n');

        // ---- 5. Knockout: group winners + playoff winners ----
        List<Integer> koField = new ArrayList<>(directQf);
        koField.addAll(playoffWinners);
        for (int t : koField) reachedQf[t] = true;
        TournamentEngine.KnockoutResult ko = engine.runKnockout(koField, powers, legFormat, drawRng);
        if (!ko.rounds().isEmpty()) {
            int firstBracket = ko.rounds().get(0).bracketSize();
            for (int t : koField) koStageReached[t] = Math.min(koStageReached[t], firstBracket);
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

        return new Outcome(champion, reachedGroup, groupPoints, groupPosition, groupWinner,
                reachedPlayoff, reachedQf, koMatchesWon, koStageReached);
    }

    // ==================== LOG FORMATTING ====================

    private static String formatKoMatch(List<TeamSetup> teams, TournamentEngine.TieOutcome t) {
        return "- " + teams.get(t.aIdx()).name() + " vs " + teams.get(t.bIdx()).name()
                + " — " + t.tie().summary()
                + "  → **" + teams.get(t.winnerIdx()).name() + "** advances";
    }

    private static String namesOf(List<TeamSetup> teams, List<Integer> idxs) {
        return idxs.stream().map(i -> teams.get(i).name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ==================== REPORT ====================

    private String buildReport(List<TeamSetup> teams, Aggregated agg, String firstEditionLog, LegFormat legFormat) {
        int n = teams.size();
        int[] koStageSizes = agg.koStageSizes();
        int[] titles = agg.titles();
        long[][] koReachedAtLeast = agg.koReachedAtLeast();

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
        sb.append("# Stars Cup Outcome Simulation\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Format: ");
        if (n > groupSlots) sb.append("preliminary rounds trim ").append(n).append(" → ").append(groupSlots).append(" teams, then ");
        sb.append(groupCount).append(" groups of ").append(groupSize)
                .append(" (double round-robin, 6 matchdays); group winners → QF, 2nd+3rd → ")
                .append(legFormat == LegFormat.TWO_LEG ? "two-leg" : "single-leg")
                .append(" playoff → ").append(koBracket).append("-team knockout (level ties → extra time → penalties)\n");
        sb.append("Teams: ").append(n).append('\n');
        sb.append("Editions simulated: ").append(TOURNAMENTS).append('\n');
        sb.append("Elapsed: ").append(agg.elapsedMs()).append(" ms\n");
        sb.append(String.format("Knockout tiebreak: extra time (~%.1f goals) then penalties (weaker team %.0f%%)%n",
                agg.etGoals(), agg.penWeakerChance() * 100));
        sb.append("Seed: ").append(BASE_SEED).append(" (deterministic — same seed → same numbers)\n\n");

        sb.append("## Results After ").append(TOURNAMENTS).append(" Editions\n\n");
        sb.append("Sorted by trophies won. \"Grp win\" = won the group (direct QF); ");
        sb.append("\"Playoff\" = finished 2nd/3rd; \"Reach QF\" = entered the quarterfinals.\n\n");
        List<String> headers = new ArrayList<>(List.of(
                "Rank", "Team", "Power", "Trophies", "Reach grp", "Grp win", "Playoff", "Reach QF",
                "Avg grp pos", "Avg grp pts"));
        List<MarkdownTable.Align> aligns = new ArrayList<>(List.of(
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        if (semiIdx >= 0) { headers.add("Semi %"); aligns.add(MarkdownTable.Align.RIGHT); }
        if (finalIdx >= 0) { headers.add("Final %"); aligns.add(MarkdownTable.Align.RIGHT); }
        headers.add("KO won");
        aligns.add(MarkdownTable.Align.RIGHT);

        MarkdownTable main = new MarkdownTable(headers, aligns);
        int rank = 1;
        for (int t : order) {
            long rg = agg.reachedGroup()[t];
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(rank++));
            row.add(teams.get(t).name());
            row.add(String.format("%.0f", teams.get(t).power()));
            row.add(pct(titles[t]));
            row.add(pct(rg));
            row.add(pct(agg.groupWinner()[t]));
            row.add(pct(agg.reachedPlayoff()[t]));
            row.add(pct(agg.reachedQf()[t]));
            row.add(rg == 0 ? "—" : String.format("%.2f", agg.groupPosTotal()[t] / (double) rg));
            row.add(rg == 0 ? "—" : String.format("%.1f", agg.groupPointsTotal()[t] / (double) rg));
            if (semiIdx >= 0) row.add(pct(koReachedAtLeast[t][semiIdx]));
            if (finalIdx >= 0) row.add(pct(koReachedAtLeast[t][finalIdx]));
            row.add(String.format("%.2f", agg.koMatchesWon()[t] / (double) TOURNAMENTS));
            main.addRow(row.toArray(new String[0]));
        }
        sb.append(main.render()).append('\n');

        sb.append("## Knockout Stage Reached (% of editions reaching at least this stage)\n\n");
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

        sb.append("# First Edition — Phase by Phase\n\n");
        sb.append("Every match of the first simulated edition, in order.\n\n");
        sb.append(firstEditionLog);

        sb.append("## How to read this report\n\n");
        sb.append("- **Power** = sum of top-11 player ratings (no morale/fitness adjustments).\n");
        sb.append("- **Trophies** = % of editions this team won the Stars Cup. Sums to 100% across all teams.\n");
        sb.append("- **Grp win** = % finished 1st in its group (straight to the QF). **Playoff** = % finished 2nd/3rd.\n");
        sb.append("- **Reach QF** = % entered the quarterfinals (group winners + playoff winners).\n");
        sb.append("- A standalone Stars Cup has no LoC drop-out feed, so the 8 playoff teams are the group 2nd/3rd places.\n");

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

    private record Outcome(int champion, boolean[] reachedGroup, int[] groupPoints, int[] groupPosition,
                           boolean[] groupWinner, boolean[] reachedPlayoff, boolean[] reachedQf,
                           int[] koMatchesWon, int[] koStageReached) {}

    private record Aggregated(int[] koStageSizes, int[] titles, long[] reachedGroup, long[] groupWinner,
                              long[] reachedPlayoff, long[] reachedQf, long[] groupPointsTotal,
                              long[] groupPosTotal, long[] koMatchesWon, long[][] koReachedAtLeast,
                              long elapsedMs, double etGoals, double penWeakerChance) {}

}
