package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.LineupRatingService;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.PlayerValueService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-tactic outcome explorer. Pick YOUR team and a full tactic (formation + mentality,
 * time-wasting, in-possession, passing, tempo); the harness simulates {@code SEASONS} full
 * league campaigns in that team's league and reports where your team finishes on average —
 * then sweeps each tactic axis one value at a time so a single run shows how every setting
 * moves your average position (relative to your squad's value).
 *
 * <p>Model (mirrors the production human path): every team's base power = its best-eleven match
 * value via {@link PlayerValueService} (always the best 11). For YOUR team's matches the base is
 * adjusted per opponent by {@link LineupRatingService#adjustTeamPowerByTacticalProperties} — the
 * mentality/tempo/etc. lever, which is opponent-relative — then goals come from
 * {@link MatchSimulationService#calculateScores}. Opponents use a neutral 4-4-2 with no tactic
 * adjustment (they stand in for the AI). Seeded RNG → fully deterministic and A/B-comparable:
 * every sweep variation replays the identical match stream, so any change in your finishing
 * position is purely the tactic change.
 *
 * <h2>How to run</h2>
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT -Dteam.id=1
 *   mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT -Dteam.id=1 \
 *       -Dformation=433 -Dmentality=Attacking -Dtempo=Higher -DpassingType=Short \
 *       -DinPossession="Keep Ball" -DtimeWasting=Never -Dseasons=100
 * </pre>
 * Output: {@code target/human-tactic-outcome-{teamId}.md}. Gated behind {@code -Pfuzz}; skipped
 * unless {@code -Dteam.id} is supplied.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Human tactic outcome — your mean league position per tactic setting over N seasons")
class HumanTacticOutcomeFuzzIT {

    private static final long BASE_SEED = 20260528L;
    private static final String OPPONENT_FORMATION = "442";

    // Tactic axis value sets (must match the strings adjustTeamPowerByTacticalProperties understands).
    private static final List<String> MENTALITIES = List.of("Very Attacking", "Attacking", "Balanced", "Defensive", "Very Defensive");
    private static final List<String> TIME_WASTING = List.of("Never", "Sometimes", "Frequently", "Always");
    private static final List<String> IN_POSSESSION = List.of("Standard", "Keep Ball", "Free Ball Early");
    private static final List<String> PASSING = List.of("Short", "Normal", "Long");
    private static final List<String> TEMPO = List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private GameStateService gameState;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private LineupRatingService lineupRatingService;
    @Autowired private TacticController tacticController;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TacticService tacticService;

    @Test
    @DisplayName("Simulate your team + tactic for N seasons, sweep each axis, write target/human-tactic-outcome-{teamId}.md")
    void simulateHumanTacticAndReport() throws Exception {
        String teamIdProp = System.getProperty("team.id");
        Assumptions.assumeTrue(teamIdProp != null && !teamIdProp.isBlank(),
                "Skipping — supply -Dteam.id=ID (your team) to run this harness");
        long humanTeamId = Long.parseLong(teamIdProp.trim());

        int seasons = Integer.getInteger("seasons", 100);
        String formation = System.getProperty("formation", "442");
        PersonalizedTactic baseTactic = buildTactic(
                System.getProperty("mentality", "Balanced"),
                System.getProperty("timeWasting", "Sometimes"),
                System.getProperty("inPossession", "Standard"),
                System.getProperty("passingType", "Normal"),
                System.getProperty("tempo", "Standard"));

        int season = gameState.currentSeason();
        long compId = findLeagueForTeam(humanTeamId, season);

        // ---- Load league + compute every team's base match value (best 11) ----
        List<Long> ids = distinctSortedTeamIds(compId, season);
        int n = ids.size();
        assertThat(n).as("league must have at least 2 teams").isGreaterThanOrEqualTo(2);

        final int humanIdx = ids.indexOf(humanTeamId);
        assertThat(humanIdx).as("your team must be in the resolved league").isGreaterThanOrEqualTo(0);

        double[] baseValues = new double[n];
        List<TeamSetup> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = ids.get(i);
            String f = id == humanTeamId ? formation : OPPONENT_FORMATION;
            double base = teamMatchValue(id, f);
            baseValues[i] = base;
            String name = teamRepo.findNameById(id);
            teams.add(new TeamSetup(id, name == null ? "Team#" + id : name, base));
        }

        int encounters = competitionFormat.get(1).encountersFor(n);

        // ---- Baseline: configured tactic ----
        Aggregate baseline = runSeasons(teams, baseValues, humanIdx, baseTactic, encounters, seasons);
        double baselineMeanPos = meanPosition(baseline.positionCounts()[humanIdx], seasons);

        // ---- Build report: baseline + per-axis sweeps (hold everything else at the configured value) ----
        StringBuilder sb = new StringBuilder();
        sb.append("# Human Tactic Outcome\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Your team: ").append(teams.get(humanIdx).name()).append(" (id=").append(humanTeamId).append(")\n");
        sb.append("League: comp id=").append(compId).append(", season=").append(season)
                .append(", ").append(n).append(" teams, ").append((n - 1) * encounters).append(" matches/season\n");
        sb.append("Seasons simulated: ").append(seasons).append("  •  Seed: ").append(BASE_SEED)
                .append(" (deterministic, A/B-comparable)\n");
        sb.append(String.format("Your squad base value (best 11, %s): %.0f%n", formation, baseValues[humanIdx]));
        sb.append("Configured tactic: ").append(describe(formation, baseTactic)).append("\n\n");
        sb.append("Valid `-D` values — formation: `")
                .append(String.join("`, `", tacticService.getAllExistingTactics())).append("`\n");
        sb.append("mentality: `").append(String.join("`, `", MENTALITIES))
                .append("`  •  tempo: `").append(String.join("`, `", TEMPO)).append("`\n");
        sb.append("passingType: `").append(String.join("`, `", PASSING))
                .append("`  •  inPossession: `").append(String.join("`, `", IN_POSSESSION))
                .append("`  •  timeWasting: `").append(String.join("`, `", TIME_WASTING)).append("`\n\n");
        sb.append(String.format("## Baseline: your mean finishing position = **%.2f / %d**  (avg pts %.1f, title %.1f%%)%n%n",
                baselineMeanPos, n,
                baseline.totalPoints()[humanIdx] / (double) seasons,
                baseline.championships()[humanIdx] * 100.0 / seasons));

        sb.append(baselineStandings(teams, baseline, humanIdx, seasons)).append('\n');

        // Sweep each tactic axis.
        sb.append("## How each setting moves your average position\n\n");
        sb.append("One axis varied at a time; everything else stays at your configured value. ");
        sb.append("Lower mean position = better. Δ is vs your baseline above (negative = better).\n\n");

        sb.append(sweepTable("Mentality", MENTALITIES, baseTactic.getMentality(), baselineMeanPos,
                v -> teamMeanPos(teams, baseValues, humanIdx, withMentality(baseTactic, v), encounters, seasons)));
        sb.append(sweepTable("Tempo", TEMPO, baseTactic.getTempo(), baselineMeanPos,
                v -> teamMeanPos(teams, baseValues, humanIdx, withTempo(baseTactic, v), encounters, seasons)));
        sb.append(sweepTable("Passing", PASSING, baseTactic.getPassingType(), baselineMeanPos,
                v -> teamMeanPos(teams, baseValues, humanIdx, withPassing(baseTactic, v), encounters, seasons)));
        sb.append(sweepTable("In possession", IN_POSSESSION, baseTactic.getInPossession(), baselineMeanPos,
                v -> teamMeanPos(teams, baseValues, humanIdx, withInPossession(baseTactic, v), encounters, seasons)));
        sb.append(sweepTable("Time wasting", TIME_WASTING, baseTactic.getTimeWasting(), baselineMeanPos,
                v -> teamMeanPos(teams, baseValues, humanIdx, withTimeWasting(baseTactic, v), encounters, seasons)));

        // Formation sweep — changes which best 11 plays (recomputes your base value).
        sb.append(formationSweep(teams, baseValues, humanIdx, humanTeamId, baseTactic, formation,
                baselineMeanPos, encounters, seasons));

        sb.append("\n## How to read this\n\n");
        sb.append("- **Mean position**: average league finish over ").append(seasons)
                .append(" seasons (1 = champion). Compare rows within an axis to see that setting's effect *for your squad*.\n");
        sb.append("- Effects are **opponent-relative**: a setting that helps a weak side overperform may not help a favourite, and vice versa.\n");
        sb.append("- Mentality/time-wasting/in-possession only bite when mentality ≠ Balanced (engine rule); passing+tempo always apply.\n");

        Path reportPath = Path.of("target", "human-tactic-outcome-" + humanTeamId + ".md");
        Files.writeString(reportPath, sb.toString());
        System.out.println();
        System.out.println(sb);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());

        // Sanity: the simulation produced a valid position for your team.
        assertThat(baselineMeanPos).isBetween(1.0, (double) n);
    }

    /**
     * Search the full tactic-setting space (mentality × time-wasting × in-possession × passing ×
     * tempo = 900 combos) for YOUR team and recommend the single best tactic — accounting for the
     * interactions that a one-axis sweep misses. The formation is auto-picked as the one that best
     * fits your players (highest best-11 match value), unless {@code -Dformation} overrides it.
     *
     * <pre>
     *   mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT#searchBestTacticAndReport -Dteam.id=104
     *   # pin the formation, or change season count:
     *   mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT#searchBestTacticAndReport \
     *       -Dteam.id=104 -Dformation=433 -Dseasons=100
     * </pre>
     * Output: {@code target/best-tactic-104.md}.
     */
    @Test
    @DisplayName("Search all 900 tactic combos and recommend the best tactic for your players")
    void searchBestTacticAndReport() throws Exception {
        String teamIdProp = System.getProperty("team.id");
        Assumptions.assumeTrue(teamIdProp != null && !teamIdProp.isBlank(),
                "Skipping — supply -Dteam.id=ID (your team) to run this search");
        long humanTeamId = Long.parseLong(teamIdProp.trim());
        int seasons = Integer.getInteger("seasons", 100);

        int season = gameState.currentSeason();
        long compId = findLeagueForTeam(humanTeamId, season);
        List<Long> ids = distinctSortedTeamIds(compId, season);
        int n = ids.size();
        final int humanIdx = ids.indexOf(humanTeamId);
        assertThat(humanIdx).as("your team must be in the resolved league").isGreaterThanOrEqualTo(0);
        int encounters = competitionFormat.get(1).encountersFor(n);

        // Opponents = neutral 4-4-2, no tactic adjustment (stand-in AI). Human slot filled after
        // the formation is chosen.
        double[] baseValues = new double[n];
        List<TeamSetup> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = ids.get(i);
            String name = teamRepo.findNameById(id);
            double base = i == humanIdx ? 0 : teamMatchValue(id, OPPONENT_FORMATION);
            baseValues[i] = base;
            teams.add(new TeamSetup(id, name == null ? "Team#" + id : name, base));
        }

        // ---- Pick the formation that best fits your players (max base value), or honour -Dformation ----
        String formationOverride = System.getProperty("formation");
        String bestFormation;
        double humanBase;
        if (formationOverride != null && !formationOverride.isBlank()) {
            bestFormation = formationOverride.trim();
            humanBase = teamMatchValue(humanTeamId, bestFormation);
        } else {
            bestFormation = null;
            humanBase = -1;
            for (String f : tacticService.getAllExistingTactics()) {
                double v = teamMatchValue(humanTeamId, f);
                if (v > humanBase) { humanBase = v; bestFormation = f; }
            }
        }
        baseValues[humanIdx] = humanBase;
        teams.set(humanIdx, new TeamSetup(humanTeamId, teams.get(humanIdx).name(), humanBase));

        // ---- Neutral-tactic baseline for reference ----
        double neutralMean = teamMeanPos(teams, baseValues, humanIdx,
                buildTactic("Balanced", "Sometimes", "Standard", "Normal", "Standard"), encounters, seasons);

        // ---- Exhaustive search over the 900 setting combinations ----
        List<TacticResult> results = new ArrayList<>(900);
        for (String mentality : MENTALITIES)
            for (String timeWasting : TIME_WASTING)
                for (String inPossession : IN_POSSESSION)
                    for (String passing : PASSING)
                        for (String tempo : TEMPO) {
                            PersonalizedTactic t = buildTactic(mentality, timeWasting, inPossession, passing, tempo);
                            Aggregate agg = runSeasons(teams, baseValues, humanIdx, t, encounters, seasons);
                            results.add(new TacticResult(t,
                                    meanPosition(agg.positionCounts()[humanIdx], seasons),
                                    agg.championships()[humanIdx] * 100.0 / seasons));
                        }
        results.sort(java.util.Comparator.comparingDouble(TacticResult::meanPos));
        TacticResult best = results.get(0);

        // ---- Report ----
        StringBuilder sb = new StringBuilder();
        sb.append("# Best Tactic Search\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Your team: ").append(teams.get(humanIdx).name()).append(" (id=").append(humanTeamId).append(")\n");
        sb.append("League: comp id=").append(compId).append(", ").append(n).append(" teams, ")
                .append((n - 1) * encounters).append(" matches/season\n");
        sb.append("Formation: ").append(bestFormation)
                .append(formationOverride != null ? " (pinned via -Dformation)" : " (auto — best fit for your players)")
                .append(String.format(", base value %.0f%n", humanBase));
        sb.append("Seasons/combo: ").append(seasons).append("  •  Combos tried: ").append(results.size())
                .append("  •  Seed: ").append(BASE_SEED).append(" (deterministic)\n");
        sb.append(String.format("Neutral-tactic baseline: mean position %.2f / %d%n%n", neutralMean, n));

        sb.append("## ★ Recommended tactic\n\n");
        sb.append(String.format("**Mean position %.2f / %d**  (title %.1f%%)  —  %+.2f vs neutral%n%n",
                best.meanPos(), n, best.titlePct(), best.meanPos() - neutralMean));
        sb.append("- Formation: `").append(bestFormation).append("`\n");
        sb.append("- Mentality: `").append(best.tactic().getMentality()).append("`\n");
        sb.append("- Tempo: `").append(best.tactic().getTempo()).append("`\n");
        sb.append("- Passing: `").append(best.tactic().getPassingType()).append("`\n");
        sb.append("- In possession: `").append(best.tactic().getInPossession()).append("`\n");
        sb.append("- Time wasting: `").append(best.tactic().getTimeWasting()).append("`\n\n");
        sb.append("Reproduce / verify:\n```\n").append(reproduceCommand(humanTeamId, bestFormation, best.tactic(), seasons))
                .append("\n```\n\n");

        sb.append("## Top 15 tactics\n\n");
        MarkdownTable table = new MarkdownTable(
                List.of("#", "Mentality", "Tempo", "Passing", "Possession", "Time-wasting", "Mean Pos", "Title %"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int i = 0; i < Math.min(15, results.size()); i++) {
            TacticResult r = results.get(i);
            table.addRow(String.valueOf(i + 1),
                    r.tactic().getMentality(), r.tactic().getTempo(), r.tactic().getPassingType(),
                    r.tactic().getInPossession(), r.tactic().getTimeWasting(),
                    String.format("%.2f", r.meanPos()), String.format("%.1f%%", r.titlePct()));
        }
        sb.append(table.render()).append('\n');

        sb.append("## Note\n\n");
        sb.append("- This searches the full 900-combo space, so it captures interactions a one-axis sweep misses ");
        sb.append("(e.g. the best passing depends on tempo; mentality gates possession/time-wasting).\n");
        sb.append("- The tactic effect is **opponent-relative** and the engine stacks the per-axis percentages, ");
        sb.append("so a strong combination can be a large swing. Tune `adjustTeamPowerByTacticalProperties` if you ");
        sb.append("want tactics to be a smaller lever relative to squad value.\n");

        Path reportPath = Path.of("target", "best-tactic-" + humanTeamId + ".md");
        Files.writeString(reportPath, sb.toString());
        System.out.println();
        System.out.println(sb);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());

        assertThat(best.meanPos()).isBetween(1.0, (double) n);
        assertThat(best.meanPos()).as("best tactic must be at least as good as neutral")
                .isLessThanOrEqualTo(neutralMean + 1e-9);
    }

    private static String reproduceCommand(long teamId, String formation, PersonalizedTactic t, int seasons) {
        return "mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT -Dteam.id=" + teamId
                + " -Dformation=" + formation
                + " -Dmentality=\"" + t.getMentality() + "\""
                + " -Dtempo=\"" + t.getTempo() + "\""
                + " -DpassingType=\"" + t.getPassingType() + "\""
                + " -DinPossession=\"" + t.getInPossession() + "\""
                + " -DtimeWasting=\"" + t.getTimeWasting() + "\""
                + " -Dseasons=" + seasons;
    }

    private record TacticResult(PersonalizedTactic tactic, double meanPos, double titlePct) {}

    // ==================== simulation ====================

    /** One team's matchday base value: best-11 match value (PlayerValueService), mirrors getSimpleTeamRating. */
    private double teamMatchValue(long teamId, String formation) {
        List<TacticController.StarterSlot> slots = tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = slots.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skills.put(s.getPlayerId(), s);

        double sum = 0;
        for (TacticController.StarterSlot slot : slots) {
            var pv = slot.player();
            String natural = pv.getPosition();
            String used = slot.usedPosition();
            PlayerSkills sk = skills.get(pv.getId());
            sum += sk != null
                    ? playerValueService.evaluatePlayer(sk, natural, used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
        }
        return sum;
    }

    /** Effective power of {@code team} when facing {@code opp}: the human team gets the tactic adjustment. */
    private double effectivePower(int team, int opp, double[] baseValues, int humanIdx, PersonalizedTactic tactic) {
        if (team == humanIdx) {
            return lineupRatingService.adjustTeamPowerByTacticalProperties(baseValues[team], baseValues[opp], tactic);
        }
        return baseValues[team];
    }

    /** Run N seasons; aggregate per-team position counts, points and titles. Seed reset each call for A/B parity. */
    private Aggregate runSeasons(List<TeamSetup> teams, double[] baseValues, int humanIdx,
                                 PersonalizedTactic tactic, int encounters, int seasons) {
        int n = teams.size();
        long[][] positionCounts = new long[n][n];
        long[] totalPoints = new long[n];
        int[] championships = new int[n];

        matchSim.setRandomForTesting(new Random(BASE_SEED));
        try {
            for (int s = 0; s < seasons; s++) {
                int[] points = new int[n], gf = new int[n], ga = new int[n];
                int fullDoubles = encounters / 2;
                boolean extraSingle = (encounters % 2) == 1;
                for (int d = 0; d < fullDoubles; d++) {
                    for (int home = 0; home < n; home++) {
                        for (int away = 0; away < n; away++) {
                            if (home == away) continue;
                            playMatch(home, away, baseValues, humanIdx, tactic, points, gf, ga);
                        }
                    }
                }
                if (extraSingle) {
                    for (int i = 0; i < n; i++)
                        for (int j = i + 1; j < n; j++)
                            playMatch(i, j, baseValues, humanIdx, tactic, points, gf, ga);
                }
                int[] order = standingsOrder(teams, points, gf, ga);
                for (int pos = 0; pos < n; pos++) positionCounts[order[pos]][pos]++;
                for (int t = 0; t < n; t++) totalPoints[t] += points[t];
                championships[order[0]]++;
            }
        } finally {
            matchSim.setRandomForTesting(new Random());
        }
        return new Aggregate(positionCounts, totalPoints, championships);
    }

    private void playMatch(int home, int away, double[] baseValues, int humanIdx, PersonalizedTactic tactic,
                           int[] points, int[] gf, int[] ga) {
        double pHome = effectivePower(home, away, baseValues, humanIdx, tactic);
        double pAway = effectivePower(away, home, baseValues, humanIdx, tactic);
        List<Integer> scores = matchSim.calculateScores(pHome, pAway);
        int sH = scores.get(0), sA = scores.get(1);
        gf[home] += sH; ga[home] += sA; gf[away] += sA; ga[away] += sH;
        if (sH > sA) points[home] += 3;
        else if (sH == sA) { points[home]++; points[away]++; }
        else points[away] += 3;
    }

    private int[] standingsOrder(List<TeamSetup> teams, int[] points, int[] gf, int[] ga) {
        int n = teams.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            if (points[a] != points[b]) return points[b] - points[a];
            int gdA = gf[a] - ga[a], gdB = gf[b] - ga[b];
            if (gdA != gdB) return gdB - gdA;
            if (gf[a] != gf[b]) return gf[b] - gf[a];
            return teams.get(a).name().compareTo(teams.get(b).name());
        });
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = idx[i];
        return order;
    }

    /** Convenience: run a variation and return only your team's mean finishing position. */
    private double teamMeanPos(List<TeamSetup> teams, double[] baseValues, int humanIdx,
                               PersonalizedTactic tactic, int encounters, int seasons) {
        Aggregate agg = runSeasons(teams, baseValues, humanIdx, tactic, encounters, seasons);
        return meanPosition(agg.positionCounts()[humanIdx], seasons);
    }

    private static double meanPosition(long[] positionRow, int seasons) {
        double mp = 0;
        for (int pos = 0; pos < positionRow.length; pos++) mp += (pos + 1) * positionRow[pos];
        return mp / seasons;
    }

    // ==================== league resolution ====================

    private long findLeagueForTeam(long teamId, int season) {
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList()) {
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season)) {
                if (cti.getTeamId() == teamId) return compId;
            }
        }
        throw new IllegalArgumentException(
                "Team " + teamId + " is not in any top-tier league for season " + season
                        + ". Available leagues: " + gameState.getLeagueCompetitionIdsCached());
    }

    private List<Long> distinctSortedTeamIds(long compId, int season) {
        TreeSet<Long> ids = new TreeSet<>();
        for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season)) {
            if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
        }
        return new ArrayList<>(ids);
    }

    // ==================== tactic builders ====================

    private static PersonalizedTactic buildTactic(String mentality, String timeWasting,
                                                  String inPossession, String passingType, String tempo) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(mentality);
        t.setTimeWasting(timeWasting);
        t.setInPossession(inPossession);
        t.setPassingType(passingType);
        t.setTempo(tempo);
        return t;
    }

    private static PersonalizedTactic copy(PersonalizedTactic t) {
        return buildTactic(t.getMentality(), t.getTimeWasting(), t.getInPossession(), t.getPassingType(), t.getTempo());
    }

    private static PersonalizedTactic withMentality(PersonalizedTactic t, String v) { var c = copy(t); c.setMentality(v); return c; }
    private static PersonalizedTactic withTempo(PersonalizedTactic t, String v) { var c = copy(t); c.setTempo(v); return c; }
    private static PersonalizedTactic withPassing(PersonalizedTactic t, String v) { var c = copy(t); c.setPassingType(v); return c; }
    private static PersonalizedTactic withInPossession(PersonalizedTactic t, String v) { var c = copy(t); c.setInPossession(v); return c; }
    private static PersonalizedTactic withTimeWasting(PersonalizedTactic t, String v) { var c = copy(t); c.setTimeWasting(v); return c; }

    private static String describe(String formation, PersonalizedTactic t) {
        return String.format("%s | %s | tempo %s | passing %s | possession %s | time-wasting %s",
                formation, t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(), t.getTimeWasting());
    }

    // ==================== report ====================

    private interface PosFn { double meanPosFor(String value); }

    private static String sweepTable(String axis, List<String> values, String configured,
                                     double baseline, PosFn fn) {
        MarkdownTable table = new MarkdownTable(
                List.of(axis, "Mean Pos", "Δ vs baseline", ""),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT));
        String bestValue = null;
        double bestPos = Double.MAX_VALUE;
        Map<String, Double> results = new java.util.LinkedHashMap<>();
        for (String v : values) {
            double mp = fn.meanPosFor(v);
            results.put(v, mp);
            if (mp < bestPos) { bestPos = mp; bestValue = v; }
        }
        for (Map.Entry<String, Double> e : results.entrySet()) {
            String v = e.getKey();
            double mp = e.getValue();
            String tag = (v.equals(configured) ? "« configured " : "") + (v.equals(bestValue) ? "★ best" : "");
            table.addRow(v, String.format("%.2f", mp), String.format("%+.2f", mp - baseline), tag.trim());
        }
        return "### " + axis + "\n\n" + table.render() + "\n";
    }

    private String formationSweep(List<TeamSetup> teams, double[] baseValues, int humanIdx, long humanTeamId,
                                  PersonalizedTactic tactic, String configuredFormation,
                                  double baseline, int encounters, int seasons) {
        double original = baseValues[humanIdx];
        MarkdownTable table = new MarkdownTable(
                List.of("Formation", "Your base value", "Mean Pos", "Δ vs baseline", ""),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT));
        String best = null;
        double bestPos = Double.MAX_VALUE;
        Map<String, double[]> results = new java.util.LinkedHashMap<>(); // formation -> [baseValue, meanPos]
        for (String f : tacticService.getAllExistingTactics()) {
            baseValues[humanIdx] = teamMatchValue(humanTeamId, f);
            double mp = teamMeanPos(teams, baseValues, humanIdx, tactic, encounters, seasons);
            results.put(f, new double[]{baseValues[humanIdx], mp});
            if (mp < bestPos) { bestPos = mp; best = f; }
        }
        baseValues[humanIdx] = original; // restore
        for (Map.Entry<String, double[]> e : results.entrySet()) {
            String f = e.getKey();
            double bv = e.getValue()[0], mp = e.getValue()[1];
            String tag = (f.equals(configuredFormation) ? "« configured " : "") + (f.equals(best) ? "★ best" : "");
            table.addRow(f, String.format("%.0f", bv), String.format("%.2f", mp),
                    String.format("%+.2f", mp - baseline), tag.trim());
        }
        return "### Formation (changes which best 11 plays → your base value)\n\n" + table.render() + "\n";
    }

    private static String baselineStandings(List<TeamSetup> teams, Aggregate agg, int humanIdx, int seasons) {
        int n = teams.size();
        double[] meanPos = new double[n];
        for (int t = 0; t < n; t++) meanPos[t] = meanPosition(agg.positionCounts()[t], seasons);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(meanPos[a], meanPos[b]));

        MarkdownTable table = new MarkdownTable(
                List.of("Rank", "Team", "Base value", "Mean Pos", "Avg Pts", "Title %"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        int rank = 1;
        for (int t : order) {
            String name = teams.get(t).name() + (t == humanIdx ? "  «YOU»" : "");
            table.addRow(
                    String.valueOf(rank++),
                    name,
                    String.format("%.0f", teams.get(t).power()),
                    String.format("%.2f", meanPos[t]),
                    String.format("%.1f", agg.totalPoints()[t] / (double) seasons),
                    String.format("%.1f%%", agg.championships()[t] * 100.0 / seasons));
        }
        return "## League standings with your configured tactic (mean over " + seasons + " seasons)\n\n" + table.render();
    }

    private record Aggregate(long[][] positionCounts, long[] totalPoints, int[] championships) {}
}
