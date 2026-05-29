package com.footballmanagergamesimulator.integration.cup;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
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

    @Autowired private HumanRepository humanRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private KnockoutTieResolver tieResolver;

    /** Leg format for every tie in the bracket; resolved from {@code -Dleg.format}. */
    private LegFormat legFormat = LegFormat.SINGLE_LEG;

    /**
     * Simulate a knockout cup of user-supplied teams. Skipped unless
     * {@code -Dteam.ids=ID1,ID2,...} is provided.
     *
     * <p>Constraints (fail-fast): even count, at least 2 teams, every ID must
     * exist in {@code teamRepo}.
     */
    @Test
    @DisplayName("Simulate custom knockout cup — supply via -Dteam.ids=ID1,ID2,...")
    void simulateCupAndReport() throws Exception {
        String idsProperty = System.getProperty(TEAM_IDS_PROPERTY);
        Assumptions.assumeTrue(idsProperty != null && !idsProperty.isBlank(),
                "Skipping — supply -Dteam.ids=ID1,ID2,... to run this test");

        legFormat = parseLegFormat(System.getProperty(LEG_FORMAT_PROPERTY));

        List<Long> teamIds = parseTeamIds(idsProperty);
        if (teamIds.size() < 2) {
            throw new IllegalArgumentException(
                    "Need at least 2 teams; got " + teamIds.size() + " (input: " + idsProperty + ")");
        }
        if (teamIds.size() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Team count must be even. Got " + teamIds.size() + " (input: " + idsProperty + ")");
        }

        List<TeamSetup> teams = loadTeamsByIds(teamIds);

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
        int nextPow2 = nextPowerOfTwo(n);
        int[] stageSizes = stageSizes(nextPow2); // [nextPow2, nextPow2/2, ..., 2]

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
                CupOutcome outcome = runOneCup(teams, drawRng, nextPow2);
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
     * One single-elimination cup. Top {@code numByes} seeds (by power) get a
     * round-one bye; the rest are drawn randomly. Bracket has {@code nextPow2}
     * slots so every round halves cleanly. Returns champion + per-team stats.
     */
    private CupOutcome runOneCup(List<TeamSetup> teams, Random drawRng, int nextPow2) {
        int n = teams.size();
        int numByes = nextPow2 - n;

        // Seed teams by power desc; strongest get the byes.
        Integer[] bySeed = new Integer[n];
        for (int i = 0; i < n; i++) bySeed[i] = i;
        java.util.Arrays.sort(bySeed, Comparator
                .comparingDouble((Integer i) -> teams.get(i).power()).reversed()
                .thenComparing(i -> teams.get(i).name()));

        List<Integer> byeTeams = new ArrayList<>(bySeed.length);
        List<Integer> playing = new ArrayList<>(bySeed.length);
        for (int i = 0; i < n; i++) {
            if (i < numByes) byeTeams.add(bySeed[i]);
            else playing.add(bySeed[i]);
        }
        Collections.shuffle(playing, drawRng);

        // Build round-one bracket: each bye paired with sentinel -1, then the
        // drawn pairs. Total slots = numByes*2 + (n - numByes) = nextPow2.
        List<Integer> alive = new ArrayList<>(nextPow2);
        for (int b : byeTeams) {
            alive.add(b);
            alive.add(-1);
        }
        alive.addAll(playing);

        int[] matchesPlayed = new int[n];
        int[] matchesWon = new int[n];
        int[] stageReached = new int[n];
        java.util.Arrays.fill(stageReached, nextPow2);

        int champion = -1;
        while (alive.size() > 1) {
            int roundSize = alive.size(); // power of 2
            for (int slot : alive) {
                if (slot >= 0 && roundSize < stageReached[slot]) {
                    stageReached[slot] = roundSize;
                }
            }
            List<Integer> next = new ArrayList<>(roundSize / 2);
            for (int i = 0; i < roundSize; i += 2) {
                int a = alive.get(i);
                int b = alive.get(i + 1);
                int winner;
                if (a == -1) {
                    winner = b;
                } else if (b == -1) {
                    winner = a;
                } else {
                    winner = playMatch(teams, a, b, drawRng);
                    matchesPlayed[a]++;
                    matchesPlayed[b]++;
                    matchesWon[winner]++;
                }
                next.add(winner);
            }
            if (roundSize == 2) champion = next.get(0);
            alive = next;
        }

        return new CupOutcome(champion, matchesPlayed, matchesWon, stageReached);
    }

    /**
     * Play a tie (single-leg or two-leg per {@link #legFormat}) through the shared
     * {@link KnockoutTieResolver}: aggregate → extra time → penalties. Returns the
     * winning index.
     */
    private int playMatch(List<TeamSetup> teams, int a, int b, Random rng) {
        return tieResolver.resolve(teams.get(a).power(), teams.get(b).power(), legFormat, rng).teamAWon()
                ? a : b;
    }

    /** Parse {@code -Dleg.format}: "single"/"single-leg" (default) or "two"/"two-leg"/"home-away". */
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

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** Bracket sizes from the opening round down to the final: [nextPow2, ..., 2]. */
    private static int[] stageSizes(int nextPow2) {
        List<Integer> sizes = new ArrayList<>();
        for (int s = nextPow2; s >= 2; s >>= 1) sizes.add(s);
        int[] out = new int[sizes.size()];
        for (int i = 0; i < out.length; i++) out[i] = sizes.get(i);
        return out;
    }

    /** Human-readable label for a bracket of {@code size} teams. */
    private static String stageLabel(int size) {
        switch (size) {
            case 2: return "Final";
            case 4: return "Semifinal";
            case 8: return "Quarterfinal";
            default: return "Round of " + size;
        }
    }

    // ==================== TEAM LOADING ====================

    /** Parse {@code "1, 5,8,  12 "} -> {@code [1, 5, 8, 12]}. Whitespace tolerated. */
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

    /** Load named teams + sort by ID for deterministic draw order. */
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

    /** Sum of top-11 non-retired player ratings for a team. */
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
            heatHeaders.add(stageLabel(size));
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

    private record TeamSetup(long id, String name, double power) {}

    private record CupOutcome(int champion, int[] matchesPlayed, int[] matchesWon, int[] stageReached) {}

    private record AggregatedCup(int nextPow2, int[] stageSizes, int[] titles,
                                 long[] matchesPlayed, long[] matchesWon,
                                 long[][] reachedAtLeast, long elapsedMs,
                                 double etGoals, double penWeakerChance) {}

    // ==================== MARKDOWN TABLE HELPER ====================

    /**
     * Builds a markdown table whose cells are padded so the raw text visually
     * aligns. Still valid markdown when rendered.
     */
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
                if (alignments.get(c) == Align.RIGHT) {
                    sb.append(bar).append(":|");
                } else {
                    sb.append(bar).append("-|");
                }
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
