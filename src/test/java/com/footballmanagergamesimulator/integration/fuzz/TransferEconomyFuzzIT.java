package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.service.TransferValueCalculator;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the <b>real</b> AI transfer pipeline
 * ({@link SeasonTransitionService#processEndOfSeason(int)} →
 * {@code EndOfSeasonProcessor}) over a multi-season campaign and reports the
 * transfer economy per strategy. Slow (plays a full league per season) → gated
 * under {@code -Pfuzz}.
 *
 * <p>Covers handoff §6.A requirements:
 * <ul>
 *   <li><b>#2</b> first-eleven market value per strategy, before vs after the campaign
 *       (which strategies grow / shrink the squad);</li>
 *   <li><b>#3</b> teams that make no transfers + the classified cause;</li>
 *   <li><b>#4</b> robustness to new / unmapped strategy ids (seeded fuzz).</li>
 * </ul>
 *
 * <p>Determinism caveat: the strategy layer is seeded via
 * {@link CompositeTransferStrategy#setRandomForTesting(Random)} and is
 * reproducible, but {@code EndOfSeasonProcessor} still has two un-seeded RNG
 * sources outside this work's scope — the contested-buyer tie-break
 * ({@code Collections.shuffle(buyers)}) and the AI-loan logic. Which strategy
 * <i>wants</i> to buy/sell (and thus the cause classification + value direction)
 * is deterministic; only contested-buyer assignment and loans are fuzzy, so the
 * assertions here are structural/directional rather than exact counts.
 *
 * <p>Report written to {@code target/transfer-economy.md}.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
class TransferEconomyFuzzIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private SeasonTransitionService seasonTransitionService;
    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private TacticService tacticService;
    @Autowired private TransferMarketService transferMarketService;

    private static final long BASE_SEED = 20260528L;
    private static final int SEASONS = 2;          // a short campaign — bump for deeper drift
    private static final long LEAGUE_TYPE_ID = 1L;

    private static final Map<Long, String> STRATEGY_NAME = Map.of(
            1L, "Academy",
            2L, "BuyYoungSellHigh",
            3L, "BuyFreeSellHigh",
            4L, "BuyMidSellMid",
            5L, "BuyTopSellWorst");

    @AfterEach
    void restoreProductionRng() {
        matchSimulationService.setRandomForTesting(new Random());
        strategy.setRandomForTesting(new Random());
    }

    // ============================================================
    //  Req #2 + #3 — campaign on the real pipeline
    // ============================================================

    @Test
    @DisplayName("First-eleven value per strategy + no-transfer causes over a campaign")
    void transferEconomyOverCampaign() throws IOException {
        long leagueCompId = competitionRepository.findIdsByTypeId(LEAGUE_TYPE_ID)
                .stream().sorted().findFirst().orElseThrow();

        // Assign a known strategy to every team so the market is active and we
        // can group the economy by strategy. (Round-robin over the 5 strategies.)
        List<Team> allTeams = teamRepository.findAll();
        Map<Long, Long> strategyByTeam = new HashMap<>();
        int s = 0;
        for (Team t : allTeams.stream().sorted(Comparator.comparingLong(Team::getId)).toList()) {
            long strat = (s++ % 5) + 1L;
            t.setStrategy(strat);
            strategyByTeam.put(t.getId(), strat);
        }
        teamRepository.saveAll(allTeams);

        Map<Long, Long> valueBefore = new HashMap<>();
        for (Team t : allTeams) valueBefore.put(t.getId(), firstElevenValue(t.getId()));

        List<String> causeReport = new ArrayList<>();

        for (int season = 0; season < SEASONS; season++) {
            int currentSeason = currentSeason();
            matchSimulationService.setRandomForTesting(new Random(BASE_SEED + season));
            strategy.setRandomForTesting(new Random(BASE_SEED + season));

            simulateLeague(leagueCompId, currentSeason);

            // Classify no-transfer causes BEFORE the pipeline mutates squads/budgets,
            // replaying the real eligibility gates (canBeTransfered + budget) against
            // the same data the pipeline is about to act on.
            List<String> causeReportThisSeason = season == 0
                    ? classifyNoTransferCauses(strategyByTeam)
                    : null;

            strategy.setRandomForTesting(new Random(BASE_SEED + season));
            seasonTransitionService.processEndOfSeason(currentSeason);

            if (season == 0) {
                causeReport = causeReportThisSeason;
            }

            seasonTransitionService.processNewSeasonSetup(currentSeason);
        }

        // Aggregate first-eleven value delta by strategy.
        Map<Long, long[]> aggByStrategy = new TreeMap<>(); // strat -> {sumBefore, sumAfter, teamCount}
        for (Team t : allTeams) {
            long strat = strategyByTeam.get(t.getId());
            long after = firstElevenValue(t.getId());
            long[] agg = aggByStrategy.computeIfAbsent(strat, k -> new long[3]);
            agg[0] += valueBefore.get(t.getId());
            agg[1] += after;
            agg[2] += 1;
        }

        String md = buildReport(leagueCompId, aggByStrategy, causeReport);
        Path report = Path.of("target", "transfer-economy.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md);
        System.out.println(md);

        // Structural invariants (robust to the residual EndOfSeasonProcessor RNG).
        for (Team t : teamRepository.findAll()) {
            assertThat(t.getTransferBudget())
                    .as("team %d budget must never go negative after transfers", t.getId())
                    .isGreaterThanOrEqualTo(0L);
        }
        long totalTransfers = transferRepository.findAll().size();
        assertThat(totalTransfers)
                .as("the campaign must execute at least some transfers (pipeline sanity)")
                .isGreaterThan(0L);
        assertThat(aggByStrategy.keySet())
                .as("every strategy must be represented among the teams")
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    // ============================================================
    //  Req #4 — robustness to new / unmapped strategy ids
    // ============================================================

    @Test
    @DisplayName("Fuzz: random (incl. invalid) strategy ids never crash + respect minimum positions")
    void strategyDispatchRobustnessFuzz() {
        HashMap<String, Integer> minPos = tacticService.getMinimumPositionNeeded();
        HashMap<String, Integer> maxPos = tacticService.getMaximumPositionAllowed();
        List<Team> teams = teamRepository.findAll();
        Set<Long> mapped = Set.of(1L, 2L, 3L, 4L, 5L);

        int iterations = 400;
        for (int i = 0; i < iterations; i++) {
            Random rng = new Random(BASE_SEED + i);
            strategy.setRandomForTesting(new Random(BASE_SEED + i));

            Team team = teams.get(rng.nextInt(teams.size()));
            long stratId = rng.nextInt(-3, 12); // spans invalid (<1, >5) and valid (1..5)
            team.setStrategy(stratId);

            List<PlayerTransferView> sold = strategy.playersToSell(team, humanRepository, minPos);
            BuyPlanTransferView plan = strategy.playersToBuy(team, humanRepository, maxPos);

            assertThat(sold).as("seed %d strat %d: sell list must never be null", i, stratId).isNotNull();

            if (!mapped.contains(stratId)) {
                assertThat(sold).as("unmapped strat %d must sell nobody", stratId).isEmpty();
                assertThat(plan).as("unmapped strat %d must have no buy plan", stratId).isNull();
                continue;
            }

            // Mapped strategy: it must never sell more than the surplus at a
            // position, i.e. soldCount[pos] <= max(0, squadCount[pos] - min[pos]).
            // (It cannot keep a position above min if the squad already starts
            // below min — it just sells nobody there.)
            List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
            Map<String, Integer> squadCount = new HashMap<>();
            for (Human h : squad) squadCount.merge(h.getPosition(), 1, Integer::sum);
            Map<String, Integer> soldCount = new HashMap<>();
            for (PlayerTransferView v : sold) soldCount.merge(v.getPosition(), 1, Integer::sum);
            for (Map.Entry<String, Integer> e : soldCount.entrySet()) {
                String pos = e.getKey();
                int have = squadCount.getOrDefault(pos, 0);
                int min = minPos.getOrDefault(pos, 0);
                assertThat(e.getValue())
                        .as("seed %d strat %d: must not sell more than surplus at %s (have %d, min %d)",
                                i, stratId, pos, have, min)
                        .isLessThanOrEqualTo(Math.max(0, have - min));
            }
        }
    }

    // ============================================================
    //  helpers
    // ============================================================

    private void simulateLeague(long leagueCompId, int season) {
        List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                leagueCompId, String.valueOf(season));
        matchdays.sort(Long::compareTo);
        for (Long md : matchdays) {
            competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(md));
        }
    }

    /**
     * Pre-pipeline diagnostic: for every AI team, replay the exact gate order the
     * {@code EndOfSeasonProcessor} buy/sell loop uses — strategy intent →
     * {@link TransferMarketService#canBeTransfered} (age/reputation/position/rating)
     * → budget ({@code fee > transferBudget}) — and classify why a team would make
     * NO transfer. A team is NOT flagged if it has a live opportunity on either side
     * (its sellers are buyable by someone, or it can buy someone).
     *
     * <p>Must be called BEFORE {@code processEndOfSeason} so the squads/budgets it
     * inspects are the same ones the pipeline is about to act on.
     */
    private List<String> classifyNoTransferCauses(Map<Long, Long> strategyByTeam) {
        HashMap<String, Integer> minPos = tacticService.getMinimumPositionNeeded();
        HashMap<String, Integer> maxPos = tacticService.getMaximumPositionAllowed();

        // 1. Build the AI sell market (position -> sellers) and per-team sell lists.
        Map<String, List<PlayerTransferView>> market = new HashMap<>();
        Map<Long, List<PlayerTransferView>> sellByTeam = new HashMap<>();
        for (Long teamId : strategyByTeam.keySet()) {
            Team t = teamRepository.findById(teamId).orElseThrow();
            List<PlayerTransferView> sold = strategy.playersToSell(t, humanRepository, minPos);
            sellByTeam.put(teamId, sold);
            for (PlayerTransferView v : sold)
                market.computeIfAbsent(v.getPosition(), k -> new ArrayList<>()).add(v);
        }

        // 2. Classify each team.
        Map<String, Integer> causeTally = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : strategyByTeam.entrySet()) {
            long teamId = e.getKey();
            long strat = e.getValue();
            Team t = teamRepository.findById(teamId).orElseThrow();
            List<PlayerTransferView> sold = sellByTeam.getOrDefault(teamId, List.of());
            BuyPlanTransferView plan = strategy.playersToBuy(t, humanRepository, maxPos);

            boolean canSell = !sold.isEmpty();
            boolean wantsBuy = plan != null && !plan.getPositions().isEmpty();

            boolean buyMatch = false;
            boolean eligibleButBroke = false;
            if (wantsBuy) {
                for (TransferPlayer want : plan.getPositions()) {
                    List<PlayerTransferView> avail = market.get(want.getPosition());
                    if (avail == null) continue;
                    for (PlayerTransferView cand : avail) {
                        if (cand.getTeamId() == teamId) continue;
                        if (!transferMarketService.canBeTransfered(cand, plan, want)) continue;
                        long fee = TransferValueCalculator.calculate(
                                cand.getAge(), cand.getPosition(), cand.getRating());
                        if (fee > t.getTransferBudget()) { eligibleButBroke = true; continue; }
                        buyMatch = true;
                        break;
                    }
                    if (buyMatch) break;
                }
            }

            if (buyMatch || canSell) continue; // has a live opportunity → not flagged

            String cause;
            if (!STRATEGY_NAME.containsKey(strat)) cause = "UNMAPPED_STRATEGY";
            else if (!wantsBuy && !canSell) cause = "NOTHING_TO_TRADE";
            else if (!wantsBuy) cause = "NO_BUY_TARGETS";
            else if (eligibleButBroke) cause = "NO_MARKET_MATCH:INSUFFICIENT_BUDGET";
            else cause = "NO_MARKET_MATCH:NO_ELIGIBLE_SELLER";
            causeTally.merge(cause, 1, Integer::sum);
        }

        MarkdownTable table = new MarkdownTable(
                List.of("Cause", "Teams"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        causeTally.forEach((c, n) -> table.addRow(c, String.valueOf(n)));
        return List.of(table.render().split("\n", -1));
    }

    private long firstElevenValue(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired())
                .sorted(Comparator.comparingDouble(Human::getRating).reversed())
                .limit(11)
                .mapToLong(p -> TransferValueCalculator.calculate(p.getAge(), p.getPosition(), p.getRating()))
                .sum();
    }

    private int currentSeason() {
        return (int) roundRepository.findById(1L).orElseThrow().getSeason();
    }

    private String buildReport(long leagueCompId, Map<Long, long[]> aggByStrategy, List<String> causeReport) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Transfer economy report\n\n");
        sb.append("- seed: ").append(BASE_SEED).append('\n');
        sb.append("- seasons (campaign length): ").append(SEASONS).append('\n');
        sb.append("- league simulated: competition ").append(leagueCompId).append('\n');
        sb.append("- value metric: Σ TransferValueCalculator over the top-11 by rating\n\n");

        sb.append("## First-eleven value per strategy (campaign delta)\n\n");
        sb.append("| Strategy | Teams | Avg before | Avg after | Avg Δ | Δ % |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Map.Entry<Long, long[]> e : aggByStrategy.entrySet()) {
            long strat = e.getKey();
            long[] a = e.getValue();
            long n = Math.max(1, a[2]);
            double before = (double) a[0] / n;
            double after = (double) a[1] / n;
            double delta = after - before;
            double pct = before == 0 ? 0 : (delta / before) * 100.0;
            sb.append(String.format("| %s (%d) | %d | %,.0f | %,.0f | %,.0f | %+.1f%% |%n",
                    STRATEGY_NAME.getOrDefault(strat, "?"), strat, a[2], before, after, delta, pct));
        }

        sb.append("\n## Teams with no transfers — cause (season 1)\n\n");
        causeReport.forEach(l -> sb.append(l).append('\n'));
        sb.append('\n');
        return sb.toString();
    }
}
