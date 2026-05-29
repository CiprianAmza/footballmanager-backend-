package com.footballmanagergamesimulator.integration.europe;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
import com.footballmanagergamesimulator.service.knockout.TieResult;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * shared {@link KnockoutTieResolver}: level ties go to extra time then penalties.
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

    private static final int GROUP_TEAMS = 16;
    private static final int GROUP_COUNT = 4;
    private static final int GROUP_SIZE = 4;
    private static final int KO_BRACKET = 8; // 4 group winners + 4 playoff winners → QF/SF/Final

    /** Double round-robin schedule for a group of 4 (local indices 0..3),
     *  6 matchdays × 2 matches; {match[0]} is home. */
    private static final int[][][] GROUP_SCHEDULE = {
            {{0, 1}, {2, 3}},
            {{0, 2}, {1, 3}},
            {{0, 3}, {1, 2}},
            {{1, 0}, {3, 2}},
            {{2, 0}, {3, 1}},
            {{3, 0}, {2, 1}},
    };

    private static final String TEAM_IDS_PROPERTY = "team.ids";
    private static final String LEG_FORMAT_PROPERTY = "leg.format";

    @Autowired private HumanRepository humanRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private KnockoutTieResolver tieResolver;

    private LegFormat legFormat = LegFormat.SINGLE_LEG;

    @Test
    @DisplayName("Simulate custom Stars Cup — supply via -Dteam.ids=ID1,ID2,...")
    void simulateStarsCupAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        legFormat = parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        List<Long> teamIds = parseTeamIds(idsProperty);
        if (teamIds.size() < GROUP_TEAMS) {
            throw new IllegalArgumentException(
                    "Need at least " + GROUP_TEAMS + " teams (the group stage always has "
                            + GROUP_TEAMS + "). Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }

        List<TeamSetup> teams = loadTeamsByIds(teamIds);

        StringBuilder firstEditionLog = new StringBuilder();
        Aggregated agg = runAggregate(teams, firstEditionLog);

        Path reportPath = Path.of("target", "stars-cup-outcome-custom-" + teams.size() + "teams.md");
        String md = buildReport(teams, agg, firstEditionLog.toString(), legFormat);
        Files.writeString(reportPath, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    // ==================== TOURNAMENT SIMULATION ====================

    private Aggregated runAggregate(List<TeamSetup> teams, StringBuilder firstEditionLog) {
        int n = teams.size();
        int[] koStageSizes = stageSizes(KO_BRACKET); // [8, 4, 2]

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
                Outcome o = runOneTournament(teams, drawRng, koStageSizes, log);
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

    /** One Stars Cup edition: preliminaries (if N>16) → groups → playoff → knockout. */
    private Outcome runOneTournament(List<TeamSetup> teams, Random drawRng,
                                     int[] koStageSizes, StringBuilder log) {
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

        // ---- 1. Preliminaries: trim to exactly 16 ----
        List<Integer> field = new ArrayList<>(List.of(seedByPower(teams)));
        int prelimRound = 1;
        while (field.size() > GROUP_TEAMS) {
            int f = field.size();
            int eliminate = Math.min(f - GROUP_TEAMS, f / 2);
            field.sort(powerDesc(teams));
            List<Integer> byes = new ArrayList<>(field.subList(0, f - 2 * eliminate));
            List<Integer> playing = new ArrayList<>(field.subList(f - 2 * eliminate, f));
            Collections.shuffle(playing, drawRng);

            if (log != null) {
                log.append("### Preliminary Round ").append(prelimRound)
                   .append(" — ").append(eliminate).append(" match(es), ")
                   .append(byes.size()).append(" bye(s)\n\n");
            }
            List<Integer> survivors = new ArrayList<>(byes);
            for (int i = 0; i < playing.size(); i += 2) {
                MatchResult r = playKnockoutMatch(teams, playing.get(i), playing.get(i + 1), drawRng);
                survivors.add(r.winner());
                if (log != null) log.append(formatKoMatch(teams, r)).append('\n');
            }
            if (log != null) {
                if (!byes.isEmpty()) log.append("- _Byes:_ ").append(namesOf(teams, byes)).append('\n');
                log.append('\n');
            }
            field = survivors;
            prelimRound++;
        }
        List<Integer> groupTeams = field; // exactly 16
        for (int t : groupTeams) reachedGroup[t] = true;

        // ---- 2. Group draw ----
        List<List<Integer>> groups = drawGroups(teams, groupTeams, drawRng);
        if (log != null) {
            log.append("## Group Stage Draw\n\n");
            for (int g = 0; g < groups.size(); g++) {
                log.append("- **Group ").append((char) ('A' + g)).append("**: ")
                   .append(namesOf(teams, groups.get(g))).append('\n');
            }
            log.append('\n');
        }

        // ---- 3. Group stage → winners (direct QF) + 2nd/3rd (playoff) ----
        List<Integer> directQf = new ArrayList<>(GROUP_COUNT);
        List<Integer> playoffTeams = new ArrayList<>(GROUP_COUNT * 2);
        playGroupStage(teams, groups, groupPoints, groupPosition, groupWinner,
                directQf, playoffTeams, log);
        for (int t : playoffTeams) reachedPlayoff[t] = true;

        // ---- 4. Playoff (round 7): 8 teams → 4 QF slots ----
        if (log != null) log.append("## Playoff (for the last 4 quarterfinal places)\n\n");
        List<Integer> playoffWinners = new ArrayList<>(GROUP_COUNT);
        List<Integer> playoffDraw = new ArrayList<>(playoffTeams);
        Collections.shuffle(playoffDraw, drawRng);
        for (int i = 0; i < playoffDraw.size(); i += 2) {
            MatchResult r = playKnockoutMatch(teams, playoffDraw.get(i), playoffDraw.get(i + 1), drawRng);
            playoffWinners.add(r.winner());
            if (log != null) log.append(formatKoMatch(teams, r)).append('\n');
        }
        if (log != null) log.append('\n');

        // ---- 5. Knockout: group winners + playoff winners ----
        List<Integer> koField = new ArrayList<>(directQf);
        koField.addAll(playoffWinners);
        for (int t : koField) reachedQf[t] = true;
        int champion = runKnockout(teams, koField, drawRng, koMatchesWon, koStageReached, log);

        return new Outcome(champion, reachedGroup, groupPoints, groupPosition, groupWinner,
                reachedPlayoff, reachedQf, koMatchesWon, koStageReached);
    }

    private List<List<Integer>> drawGroups(List<TeamSetup> teams, List<Integer> groupTeams, Random drawRng) {
        List<Integer> seeded = new ArrayList<>(groupTeams);
        seeded.sort(powerDesc(teams));
        List<List<Integer>> groups = new ArrayList<>(GROUP_COUNT);
        for (int g = 0; g < GROUP_COUNT; g++) groups.add(new ArrayList<>(GROUP_SIZE));
        for (int pot = 0; pot < GROUP_SIZE; pot++) {
            List<Integer> potTeams = new ArrayList<>(seeded.subList(pot * GROUP_COUNT, (pot + 1) * GROUP_COUNT));
            Collections.shuffle(potTeams, drawRng);
            for (int g = 0; g < GROUP_COUNT; g++) groups.get(g).add(potTeams.get(g));
        }
        return groups;
    }

    /**
     * Play the 4 groups; fill {@code directQf} with each group's winner and
     * {@code playoffTeams} with each group's 2nd + 3rd. Records points/position
     * and marks group winners. 4th place is eliminated.
     */
    private void playGroupStage(List<TeamSetup> teams, List<List<Integer>> groups,
                                int[] groupPoints, int[] groupPosition, boolean[] groupWinner,
                                List<Integer> directQf, List<Integer> playoffTeams, StringBuilder log) {
        int g = groups.size();
        int[][] pts = new int[g][GROUP_SIZE];
        int[][] gf = new int[g][GROUP_SIZE];
        int[][] ga = new int[g][GROUP_SIZE];

        for (int md = 0; md < GROUP_SCHEDULE.length; md++) {
            if (log != null) log.append("### Group Stage — Matchday ").append(md + 1).append("\n\n");
            for (int gi = 0; gi < g; gi++) {
                List<Integer> group = groups.get(gi);
                for (int[] pair : GROUP_SCHEDULE[md]) {
                    int homeLocal = pair[0], awayLocal = pair[1];
                    int homeIdx = group.get(homeLocal), awayIdx = group.get(awayLocal);
                    List<Integer> scores = matchSim.calculateScores(
                            teams.get(homeIdx).power(), teams.get(awayIdx).power());
                    int sH = scores.get(0), sA = scores.get(1);
                    gf[gi][homeLocal] += sH; ga[gi][homeLocal] += sA;
                    gf[gi][awayLocal] += sA; ga[gi][awayLocal] += sH;
                    if (sH > sA) pts[gi][homeLocal] += 3;
                    else if (sH == sA) { pts[gi][homeLocal]++; pts[gi][awayLocal]++; }
                    else pts[gi][awayLocal] += 3;
                    if (log != null) {
                        log.append("- [Group ").append((char) ('A' + gi)).append("] ")
                           .append(teams.get(homeIdx).name()).append(" ").append(sH).append("–").append(sA)
                           .append(" ").append(teams.get(awayIdx).name()).append('\n');
                    }
                }
            }
            if (log != null) log.append('\n');
        }

        for (int gi = 0; gi < g; gi++) {
            List<Integer> group = groups.get(gi);
            Integer[] localOrder = new Integer[GROUP_SIZE];
            for (int i = 0; i < GROUP_SIZE; i++) localOrder[i] = i;
            final int gg = gi;
            java.util.Arrays.sort(localOrder, (a, b) -> {
                if (pts[gg][a] != pts[gg][b]) return pts[gg][b] - pts[gg][a];
                int gdA = gf[gg][a] - ga[gg][a], gdB = gf[gg][b] - ga[gg][b];
                if (gdA != gdB) return gdB - gdA;
                if (gf[gg][a] != gf[gg][b]) return gf[gg][b] - gf[gg][a];
                return Double.compare(teams.get(group.get(b)).power(), teams.get(group.get(a)).power());
            });
            if (log != null) log.append("**Group ").append((char) ('A' + gi)).append(" final standings:**\n\n");
            for (int pos = 0; pos < GROUP_SIZE; pos++) {
                int local = localOrder[pos];
                int teamIdx = group.get(local);
                groupPoints[teamIdx] += pts[gi][local];
                groupPosition[teamIdx] = pos + 1;
                String tag = "";
                if (pos == 0) { groupWinner[teamIdx] = true; directQf.add(teamIdx); tag = "  ✅ → QF"; }
                else if (pos <= 2) { playoffTeams.add(teamIdx); tag = "  ↘ playoff"; }
                else tag = "  ✗ out";
                if (log != null) {
                    log.append("  ").append(pos + 1).append(". ").append(teams.get(teamIdx).name())
                       .append(" — ").append(pts[gi][local]).append(" pts (")
                       .append(gf[gi][local]).append('-').append(ga[gi][local]).append(')')
                       .append(tag).append('\n');
                }
            }
            if (log != null) log.append('\n');
        }
    }

    private int runKnockout(List<TeamSetup> teams, List<Integer> koField, Random drawRng,
                            int[] koMatchesWon, int[] koStageReached, StringBuilder log) {
        List<Integer> alive = new ArrayList<>(koField);
        Collections.shuffle(alive, drawRng);
        for (int slot : alive) koStageReached[slot] = Math.min(koStageReached[slot], alive.size());

        int champion = -1;
        while (alive.size() > 1) {
            int roundSize = alive.size();
            if (log != null) log.append("### ").append(stageLabel(roundSize)).append("\n\n");
            List<Integer> next = new ArrayList<>(roundSize / 2);
            for (int i = 0; i < roundSize; i += 2) {
                MatchResult r = playKnockoutMatch(teams, alive.get(i), alive.get(i + 1), drawRng);
                koMatchesWon[r.winner()]++;
                next.add(r.winner());
                if (log != null) log.append(formatKoMatch(teams, r)).append('\n');
            }
            if (log != null) log.append('\n');
            for (int slot : next) koStageReached[slot] = Math.min(koStageReached[slot], roundSize / 2);
            if (roundSize == 2) champion = next.get(0);
            alive = next;
        }
        if (log != null && champion >= 0) {
            log.append("## 🏆 Champion: ").append(teams.get(champion).name()).append("\n\n");
        }
        return champion;
    }

    /** Knockout tie (single-leg or two-leg per {@link #legFormat}) via the shared resolver. */
    private MatchResult playKnockoutMatch(List<TeamSetup> teams, int a, int b, Random rng) {
        TieResult tie = tieResolver.resolve(teams.get(a).power(), teams.get(b).power(), legFormat, rng);
        int winner = tie.teamAWon() ? a : b;
        return new MatchResult(a, b, winner, tie);
    }

    private static LegFormat parseLegFormat(String value) {
        if (value == null || value.isBlank()) return LegFormat.SINGLE_LEG;
        switch (value.trim().toLowerCase()) {
            case "single":
            case "single-leg":
            case "one":
                return LegFormat.SINGLE_LEG;
            case "two":
            case "two-leg":
            case "twoleg":
            case "home-away":
                return LegFormat.TWO_LEG;
            default:
                throw new IllegalArgumentException(
                        "Invalid -Dleg.format='" + value + "'. Use 'single' or 'two-leg'.");
        }
    }

    private Integer[] seedByPower(List<TeamSetup> teams) {
        Integer[] bySeed = new Integer[teams.size()];
        for (int i = 0; i < bySeed.length; i++) bySeed[i] = i;
        java.util.Arrays.sort(bySeed, powerDesc(teams));
        return bySeed;
    }

    private Comparator<Integer> powerDesc(List<TeamSetup> teams) {
        return Comparator.comparingDouble((Integer i) -> teams.get(i).power()).reversed()
                .thenComparing(i -> teams.get(i).name());
    }

    private static int[] stageSizes(int koBracket) {
        List<Integer> sizes = new ArrayList<>();
        for (int s = koBracket; s >= 2; s >>= 1) sizes.add(s);
        int[] out = new int[sizes.size()];
        for (int i = 0; i < out.length; i++) out[i] = sizes.get(i);
        return out;
    }

    private static String stageLabel(int size) {
        switch (size) {
            case 2: return "Final";
            case 4: return "Semifinal";
            case 8: return "Quarterfinal";
            default: return "Round of " + size;
        }
    }

    // ==================== LOG FORMATTING ====================

    private static String formatKoMatch(List<TeamSetup> teams, MatchResult r) {
        return "- " + teams.get(r.aIdx()).name() + " vs " + teams.get(r.bIdx()).name()
                + " — " + r.tie().summary()
                + "  → **" + teams.get(r.winner()).name() + "** advances";
    }

    private static String namesOf(List<TeamSetup> teams, List<Integer> idxs) {
        return idxs.stream().map(i -> teams.get(i).name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ==================== TEAM LOADING ====================

    private static List<Long> parseTeamIds(String input) {
        List<Long> ids = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid -Dteam.ids — '" + trimmed + "' is not an integer. "
                                + "Expected comma-separated team IDs (e.g. \"1,5,8,12\"). Input was: \"" + input + "\"");
            }
        }
        return ids;
    }

    private List<TeamSetup> loadTeamsByIds(List<Long> teamIds) {
        java.util.TreeSet<Long> sortedIds = new java.util.TreeSet<>(teamIds);
        List<TeamSetup> out = new ArrayList<>(sortedIds.size());
        for (long id : sortedIds) {
            String name = teamRepo.findNameById(id);
            if (name == null) {
                throw new IllegalArgumentException(
                        "Team ID " + id + " not found in DB. Check -Dteam.ids — IDs must reference existing Team rows.");
            }
            out.add(new TeamSetup(id, name, computeTeamPower(id)));
        }
        return out;
    }

    private double computeTeamPower(long teamId) {
        List<Human> players = humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        players.sort(Comparator.comparingDouble(Human::getRating).reversed());
        double sum = 0;
        int count = 0;
        for (Human p : players) {
            if (p.isRetired()) continue;
            sum += p.getRating();
            if (++count == 11) break;
        }
        return sum;
    }

    // ==================== REPORT ====================

    private static String buildReport(List<TeamSetup> teams, Aggregated agg, String firstEditionLog, LegFormat legFormat) {
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
        if (n > GROUP_TEAMS) sb.append("preliminary rounds trim ").append(n).append(" → ").append(GROUP_TEAMS).append(" teams, then ");
        sb.append(GROUP_COUNT).append(" groups of ").append(GROUP_SIZE)
                .append(" (double round-robin, 6 matchdays); group winners → QF, 2nd+3rd → ")
                .append(legFormat == LegFormat.TWO_LEG ? "two-leg" : "single-leg")
                .append(" playoff → ").append(KO_BRACKET).append("-team knockout (level ties → extra time → penalties)\n");
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
            heatHeaders.add(stageLabel(size));
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

    private record TeamSetup(long id, String name, double power) {}

    private record MatchResult(int aIdx, int bIdx, int winner, TieResult tie) {}

    private record Outcome(int champion, boolean[] reachedGroup, int[] groupPoints, int[] groupPosition,
                           boolean[] groupWinner, boolean[] reachedPlayoff, boolean[] reachedQf,
                           int[] koMatchesWon, int[] koStageReached) {}

    private record Aggregated(int[] koStageSizes, int[] titles, long[] reachedGroup, long[] groupWinner,
                              long[] reachedPlayoff, long[] reachedQf, long[] groupPointsTotal,
                              long[] groupPosTotal, long[] koMatchesWon, long[][] koReachedAtLeast,
                              long elapsedMs, double etGoals, double penWeakerChance) {}

    // ==================== MARKDOWN TABLE HELPER ====================

    private static final class MarkdownTable {
        enum Align { LEFT, RIGHT }
        private final List<String> headers;
        private final List<Align> alignments;
        private final List<String[]> rows = new ArrayList<>();

        MarkdownTable(List<String> headers, List<Align> alignments) {
            if (headers.size() != alignments.size()) {
                throw new IllegalArgumentException("headers and alignments must have same size");
            }
            this.headers = List.copyOf(headers);
            this.alignments = List.copyOf(alignments);
        }

        void addRow(String... cells) {
            if (cells.length != headers.size()) {
                throw new IllegalArgumentException(
                        "row has " + cells.length + " cells but table has " + headers.size() + " columns");
            }
            rows.add(cells);
        }

        String render() {
            int cols = headers.size();
            int[] widths = new int[cols];
            for (int c = 0; c < cols; c++) widths[c] = headers.get(c).length();
            for (String[] row : rows) {
                for (int c = 0; c < cols; c++) widths[c] = Math.max(widths[c], row[c].length());
            }
            StringBuilder sb = new StringBuilder();
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(pad(headers.get(c), widths[c], alignments.get(c))).append(" |");
            }
            sb.append('\n');
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String bar = "-".repeat(widths[c] + 1);
                sb.append(bar).append(alignments.get(c) == Align.RIGHT ? ":|" : "-|");
            }
            sb.append('\n');
            for (String[] row : rows) {
                sb.append('|');
                for (int c = 0; c < cols; c++) {
                    sb.append(' ').append(pad(row[c], widths[c], alignments.get(c))).append(" |");
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        private static String pad(String s, int width, Align align) {
            if (s.length() >= width) return s;
            String padding = " ".repeat(width - s.length());
            return align == Align.RIGHT ? padding + s : s + padding;
        }
    }
}
