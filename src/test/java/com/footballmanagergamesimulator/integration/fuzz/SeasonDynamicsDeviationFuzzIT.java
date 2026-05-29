package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import com.footballmanagergamesimulator.service.tournament.TournamentEngine;
import com.footballmanagergamesimulator.testutil.OutcomeTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-season deviation study: how far does the REAL match pipeline push a
 * league's final table away from a pure "sum of top-11 ratings" power ordering,
 * and how much of that drift is contributed by the dynamics (morale evolution,
 * home advantage, fitness, injuries) on top of raw Poisson score variance?
 *
 * <p>For each simulated season:
 * <ol>
 *   <li>Snapshot the static-power ordering at season start
 *       ({@link OutcomeTestSupport#computeTeamPower} = sum top-11).</li>
 *   <li><b>Real pipeline</b>: simulate every matchday via
 *       {@link CompetitionController#simulateRound} (morale/home/fitness/injuries
 *       all flow), then read final standings from {@link TeamCompetitionDetail}.</li>
 *   <li><b>Static baseline</b>: replay the same powers through
 *       {@link TournamentEngine#playLeague} (Poisson variance only — no morale,
 *       no home advantage).</li>
 *   <li>Measure both orderings against the power ordering: normalised Spearman
 *       footrule, Kendall-τ, and champion-is-favourite.</li>
 *   <li>Advance the season via {@link SeasonTransitionService}.</li>
 * </ol>
 *
 * <p>The gap between the real and baseline footrule isolates the dynamics'
 * contribution. Results are written to {@code target/season-dynamics-deviation.md}.
 *
 * <p>Slow (drives the real pipeline across whole seasons) → gated behind
 * {@code mvn verify -Pfuzz} via the {@code *FuzzIT} name. Sibling of
 * {@link ChampionshipPredictionFuzzIT}, which uses the same matchday-loop mechanism.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Season dynamics deviation — real pipeline vs static power ordering")
class SeasonDynamicsDeviationFuzzIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private TeamCompetitionDetailRepository tcdRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private SeasonTransitionService seasonTransitionService;
    @Autowired private OutcomeTestSupport support;
    @Autowired private TournamentEngine engine;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private CompetitionFormatConfig competitionFormat;

    private static final int SEASONS = 3;
    private static final long BASE_SEED = 20260528L;
    private static final long LEAGUE_TYPE_ID = 1L;

    @Test
    @DisplayName("Measure standings deviation from power ordering across full seasons + write report")
    void measureSeasonDeviation() throws Exception {
        long leagueCompId = competitionRepository.findIdsByTypeId(LEAGUE_TYPE_ID)
                .stream().sorted().findFirst()
                .orElseThrow(() -> new IllegalStateException("No type-1 league bootstrapped"));

        List<SeasonMetrics> realMetrics = new ArrayList<>();
        List<SeasonMetrics> baselineMetrics = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("# Season Dynamics Deviation Report\n\n");
        report.append("League competition id: ").append(leagueCompId)
                .append(" | seasons: ").append(SEASONS)
                .append(" | base seed: ").append(BASE_SEED).append("\n\n");
        report.append("Deviation of the final table vs the static power ordering (sum top-11 ratings).\n")
                .append("`real` = full pipeline (morale/home/fitness/injuries); ")
                .append("`base` = Poisson-only (TournamentEngine.playLeague).\n\n");

        try {
            for (int s = 0; s < SEASONS; s++) {
                int currentSeason = (int) currentSeason();

                // Teams + static powers at the START of this season.
                List<Long> teamIds = leagueTeamIds(leagueCompId, currentSeason);
                assertThat(teamIds).as("league %d must have teams in season %d", leagueCompId, currentSeason)
                        .hasSizeGreaterThanOrEqualTo(2);
                Map<Long, Double> powerByTeam = new LinkedHashMap<>();
                for (long id : teamIds) powerByTeam.put(id, support.computeTeamPower(id));
                List<Long> powerOrder = new ArrayList<>(teamIds);
                powerOrder.sort(Comparator.comparingDouble((Long id) -> powerByTeam.get(id)).reversed()
                        .thenComparingLong(id -> id));

                // ---- Real pipeline: simulate every matchday, then read final standings. ----
                matchSim.setRandomForTesting(new Random(BASE_SEED + s * 2L));
                matchRoundSimulator.setRandomForTesting(new Random(BASE_SEED + s * 2L + 1));
                List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                        leagueCompId, String.valueOf(currentSeason));
                matchdays.sort(Long::compareTo);
                for (Long md : matchdays) {
                    competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(md));
                }
                List<Long> realOrder = realStandings(leagueCompId, teamIds);

                // ---- Static baseline: same powers, Poisson-only league. ----
                List<Long> baselineOrder = staticBaselineOrder(powerOrder, powerByTeam, BASE_SEED + 100 + s);

                SeasonMetrics real = metrics(realOrder, powerOrder);
                SeasonMetrics base = metrics(baselineOrder, powerOrder);
                realMetrics.add(real);
                baselineMetrics.add(base);

                appendSeasonSection(report, currentSeason, teamIds.size(), powerOrder, realOrder,
                        baselineOrder, powerByTeam, real, base);

                if (s < SEASONS - 1) {
                    seasonTransitionService.processEndOfSeason(currentSeason);
                    seasonTransitionService.processNewSeasonSetup(currentSeason);
                }
            }
        } finally {
            matchSim.setRandomForTesting(new Random());
            matchRoundSimulator.setRandomForTesting(new Random());
        }

        double avgRealFootrule = realMetrics.stream().mapToDouble(m -> m.footrule).average().orElse(0);
        double avgBaseFootrule = baselineMetrics.stream().mapToDouble(m -> m.footrule).average().orElse(0);
        double avgRealTau = realMetrics.stream().mapToDouble(m -> m.kendallTau).average().orElse(0);
        double avgBaseTau = baselineMetrics.stream().mapToDouble(m -> m.kendallTau).average().orElse(0);
        double realFavRate = realMetrics.stream().mapToDouble(m -> m.championIsFavourite ? 1 : 0).average().orElse(0);
        double baseFavRate = baselineMetrics.stream().mapToDouble(m -> m.championIsFavourite ? 1 : 0).average().orElse(0);

        report.append("\n## Summary (averaged over ").append(SEASONS).append(" seasons)\n\n");
        report.append("| Ordering | Norm. footrule | Kendall-τ | Champion = favourite |\n");
        report.append("|---|---|---|---|\n");
        report.append(String.format("| real (pipeline) | %.4f | %.4f | %.0f%% |%n",
                avgRealFootrule, avgRealTau, realFavRate * 100));
        report.append(String.format("| base (Poisson)  | %.4f | %.4f | %.0f%% |%n",
                avgBaseFootrule, avgBaseTau, baseFavRate * 100));
        report.append(String.format("%nDynamics contribution (real − base footrule): %.4f%n",
                avgRealFootrule - avgBaseFootrule));

        Path reportPath = Path.of("target", "season-dynamics-deviation.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString());
        System.out.println("Season deviation report written to: " + reportPath.toAbsolutePath());
        System.out.print(report);

        // Sane bounds (this is an exploratory measurement, not a tight gate):
        // 1. The real table never perfectly mirrors power, but power still dominates.
        assertThat(avgRealFootrule)
                .as("real standings must deviate from pure power ordering (footrule=%.4f)", avgRealFootrule)
                .isGreaterThan(0.0);
        assertThat(avgRealFootrule)
                .as("real footrule must stay below the random-shuffle ceiling (footrule=%.4f)", avgRealFootrule)
                .isLessThan(1.0);
        assertThat(avgRealTau)
                .as("power must remain positively correlated with the final table (τ=%.4f)", avgRealTau)
                .isGreaterThanOrEqualTo(0.1);
        // 2. Dynamics + variance move the table at least on the same order as pure Poisson noise.
        assertThat(avgRealFootrule)
                .as("real deviation (%.4f) must be at least half the Poisson-only baseline (%.4f)",
                        avgRealFootrule, avgBaseFootrule)
                .isGreaterThanOrEqualTo(avgBaseFootrule * 0.5);
    }

    // ==================== season helpers ====================

    private long currentSeason() {
        Round round = roundRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Round id=1 missing — bootstrap didn't run?"));
        return round.getSeason();
    }

    /** Team ids registered in this league for the season (available at season start,
     *  before any standings rows are created). Deduped + sorted for a stable order. */
    private List<Long> leagueTeamIds(long competitionId, int season) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (CompetitionTeamInfo cti : ctiRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
            if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
        }
        return new ArrayList<>(ids);
    }

    /** Final standings (team ids) limited to {@code teamIds}, ordered points → GD → GF,
     *  ties by id. Any team without a standings row is appended (defensive — a full
     *  league season always produces a row per team). */
    private List<Long> realStandings(long competitionId, List<Long> teamIds) {
        java.util.Set<Long> known = new java.util.HashSet<>(teamIds);
        List<Long> ordered = tcdRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && known.contains(d.getTeamId()))
                .sorted(Comparator
                        .comparingInt(TeamCompetitionDetail::getPoints).reversed()
                        .thenComparing(Comparator.comparingInt(this::goalDiff).reversed())
                        .thenComparing(Comparator.comparingInt(TeamCompetitionDetail::getGoalsFor).reversed())
                        .thenComparingLong(TeamCompetitionDetail::getTeamId))
                .map(TeamCompetitionDetail::getTeamId)
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (Long id : teamIds) {
            if (!ordered.contains(id)) ordered.add(id);
        }
        return ordered;
    }

    private int goalDiff(TeamCompetitionDetail d) {
        return d.getGoalsFor() - d.getGoalsAgainst();
    }

    /** Replay the same powers through the Poisson-only engine and return the team-id ordering. */
    private List<Long> staticBaselineOrder(List<Long> teamIdsByPower, Map<Long, Double> powerByTeam, long seed) {
        // Index teams in a stable order (by id) so the power array lines up with team ids.
        List<Long> idsSorted = new ArrayList<>(teamIdsByPower);
        idsSorted.sort(Long::compareTo);
        int n = idsSorted.size();
        double[] powers = new double[n];
        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            powers[i] = powerByTeam.get(idsSorted.get(i));
            idx.add(i);
        }
        int encounters = competitionFormat.get(1).encountersFor(n);

        // matchSim RNG is restored to a fresh Random by the caller's finally block.
        matchSim.setRandomForTesting(new Random(seed));
        TournamentEngine.LeagueTally tally = engine.playLeague(idx, powers, encounters);

        int[] points = tally.points();
        int[] gf = tally.goalsFor();
        int[] ga = tally.goalsAgainst();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            if (points[a] != points[b]) return points[b] - points[a];
            int gdA = gf[a] - ga[a], gdB = gf[b] - ga[b];
            if (gdA != gdB) return gdB - gdA;
            if (gf[a] != gf[b]) return gf[b] - gf[a];
            return Long.compare(idsSorted.get(a), idsSorted.get(b));
        });
        List<Long> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(idsSorted.get(order[i]));
        return result;
    }

    // ==================== deviation metrics ====================

    private SeasonMetrics metrics(List<Long> order, List<Long> powerOrder) {
        int n = powerOrder.size();
        Map<Long, Integer> powerRank = rankMap(powerOrder);
        Map<Long, Integer> actualRank = rankMap(order);

        // Normalised Spearman footrule: Σ|rankActual − rankPower| / maxFootrule.
        long footrule = 0;
        for (Long id : powerOrder) {
            footrule += Math.abs(actualRank.get(id) - powerRank.get(id));
        }
        double maxFootrule = Math.floor(n * n / 2.0);
        double normFootrule = maxFootrule > 0 ? footrule / maxFootrule : 0.0;

        // Kendall-τ between the two orderings.
        long concordant = 0, discordant = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long a = powerOrder.get(i), b = powerOrder.get(j);
                int pa = powerRank.get(a), pb = powerRank.get(b);
                int aa = actualRank.get(a), ab = actualRank.get(b);
                int powerCmp = Integer.compare(pa, pb);
                int actualCmp = Integer.compare(aa, ab);
                if (powerCmp == actualCmp) concordant++; else discordant++;
            }
        }
        long totalPairs = (long) n * (n - 1) / 2;
        double tau = totalPairs > 0 ? (double) (concordant - discordant) / totalPairs : 0.0;

        boolean championIsFavourite = !order.isEmpty() && order.get(0).equals(powerOrder.get(0));
        return new SeasonMetrics(normFootrule, tau, championIsFavourite);
    }

    private Map<Long, Integer> rankMap(List<Long> order) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) ranks.put(order.get(i), i);
        return ranks;
    }

    private void appendSeasonSection(StringBuilder report, int season, int teamCount,
                                     List<Long> powerOrder, List<Long> realOrder, List<Long> baselineOrder,
                                     Map<Long, Double> powerByTeam, SeasonMetrics real, SeasonMetrics base) {
        report.append("## Season ").append(season).append(" (").append(teamCount).append(" teams)\n\n");
        report.append(String.format("real: footrule=%.4f τ=%.4f champ=fav:%b | base: footrule=%.4f τ=%.4f champ=fav:%b%n%n",
                real.footrule, real.kendallTau, real.championIsFavourite,
                base.footrule, base.kendallTau, base.championIsFavourite));
        report.append("| Power rank | Team | Power | Real rank | Base rank |\n");
        report.append("|---|---|---|---|---|\n");
        Map<Long, Integer> realRank = rankMap(realOrder);
        Map<Long, Integer> baseRank = rankMap(baselineOrder);
        for (int i = 0; i < powerOrder.size(); i++) {
            long id = powerOrder.get(i);
            report.append(String.format("| %d | %s | %.0f | %d | %d |%n",
                    i + 1, teamName(id), powerByTeam.get(id),
                    realRank.get(id) + 1, baseRank.get(id) + 1));
        }
        report.append("\n");
    }

    private String teamName(long teamId) {
        return teamRepository.findById(teamId).map(t -> t.getName()).orElse("Team#" + teamId);
    }

    private record SeasonMetrics(double footrule, double kendallTau, boolean championIsFavourite) {}
}
