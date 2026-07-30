package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.TransferMarketDiagnostics;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.SquadDepthChart;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that each AI transfer strategy honours its named intent and never
 * sells below {@link TacticService#getMinimumPositionNeeded()}.
 *
 * <p>Calls the strategies <b>directly</b> via {@link CompositeTransferStrategy}
 * (bypassing the residual non-determinism in {@code EndOfSeasonProcessor}) with a
 * seeded RNG threaded through {@code setRandomForTesting}, so every assertion is
 * 100% reproducible. Fast (no season is simulated) → runs in the default
 * {@code mvn verify} gate.
 *
 * <p>The strategies now choose a <b>set</b> — the club's best XI or its reserves —
 * and then an order within it. That makes most assertions here membership tests
 * rather than comparisons of average ratings: "Academy sold a starter" is an exact
 * property, where "Academy's sold average beat the squad average" only held on
 * average and was a coin flip on any single seed.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
// Force a fresh context (and thus a freshly bootstrapped H2 DB) before this
// class. Several other ITs share bootstrap.seed=20260528 and therefore the same
// cached Spring context + DB; those that simulate matches/transfers mutate the
// "fullest squad" this class reads, breaking its pristine-squad assumption when
// they run first. BEFORE_CLASS guarantees the pristine bootstrap the assertions
// below depend on, regardless of IT execution order.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Transfer strategies: sell/buy intent + minimum-position invariant")
class TransferStrategyIT {

    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TacticService tacticService;
    @Autowired private TransferMarketService transferMarketService;
    @Autowired private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private MatchEngineConfig matchEngineConfig;

    private static final long SEED = 20260528L;

    private static final Map<Long, String> STRATEGY_NAME = new LinkedHashMap<>(Map.of(
            TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, "Academy",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, "BuyYoungSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, "BuyFreeSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, "BuyMidSellMid",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, "BuyTopSellWorst",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_TOP, "BuyTopSellTop"));

    private Team team;                       // the team with the largest squad (most sell surplus)
    private List<Human> squad;               // its players
    private SquadDepthChart depthChart;      // its best XI + reserves, from the match engine
    private Map<String, Integer> minPos;     // minimum per-position coverage
    private Map<String, Integer> maxPos;     // per-position roster caps

    @BeforeEach
    void pickFullestSquadTeam() {
        minPos = tacticService.getMinimumPositionNeeded();
        maxPos = tacticService.getMaximumPositionAllowed();

        team = teamRepository.findAll().stream()
                .max(Comparator.comparingInt(t ->
                        humanRepository.findAllByTeamIdAndTypeId(t.getId(), TypeNames.PLAYER_TYPE).size()))
                .orElseThrow(() -> new IllegalStateException("No teams — bootstrap didn't run?"));
        squad = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
        depthChart = TransferMarketDiagnostics.depthChartFor(
                team, humanRepository, matchSimulationOrchestrator, matchEngineConfig);

        assertThat(squad.size())
                .as("fullest squad must be large enough to expose sell surplus")
                .isGreaterThanOrEqualTo(16);
        assertThat(depthChart.starters())
                .as("the match engine must be able to field an XI for the fullest squad")
                .hasSize(11);
    }

    @AfterEach
    void restoreProductionRng() {
        strategy.setRandomForTesting(new Random());
    }

    // ============================================================
    //  Selling intent — which SET each strategy draws from
    // ============================================================

    @Test
    @DisplayName("Academy sells starters; BuyTopSellWorst sells reserves")
    void sellSourcesAreDisjointForTheOpinionatedStrategies() {
        Set<Long> starterIds = depthChart.starterIds();

        List<PlayerTransferView> academySold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY);
        List<PlayerTransferView> worstSold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST);

        assertThat(academySold).as("Academy must pick players to sell").isNotEmpty();
        assertThat(worstSold).as("BuyTopSellWorst must pick players to sell").isNotEmpty();

        assertThat(academySold)
                .as("Academy cashes in on the players it develops — every sale comes from the XI")
                .allMatch(sold -> starterIds.contains(sold.getPlayerId()))
                .allMatch(PlayerTransferView::isStarter);
        assertThat(worstSold)
                .as("BuyTopSellWorst clears the bench — no sale may come from the XI")
                .noneMatch(sold -> starterIds.contains(sold.getPlayerId()))
                .noneMatch(PlayerTransferView::isStarter);
    }

    @Test
    @DisplayName("Academy sells its BEST starters, BuyTopSellWorst its WORST reserves")
    void orderingWithinTheSetIsHonoured() {
        // Compared against an exact oracle, not against the set mean. The positional
        // minimum can force a strategy to skip the very player it wants most — if the
        // squad has only two centre-backs it may not sell either, however highly it
        // rates them — so "sold average beats the set average" is not something the
        // algorithm guarantees. What it does guarantee is that, among the players it
        // was legally allowed to move, it took them in its stated order.
        List<PlayerTransferView> academySold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY);
        List<PlayerTransferView> worstSold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST);

        assertThat(ratingSum(academySold))
                .as("Academy must take the highest-rated legally sellable starters")
                .isEqualTo(optimalSellSum(depthChart.starters(),
                        Comparator.comparingDouble(Human::getRating).reversed(),
                        academySold.size()), org.assertj.core.data.Offset.offset(0.001));

        assertThat(ratingSum(worstSold))
                .as("BuyTopSellWorst must take the lowest-rated legally sellable reserves")
                .isEqualTo(optimalSellSum(depthChart.reserves(),
                        Comparator.comparingDouble(Human::getRating),
                        worstSold.size()), org.assertj.core.data.Offset.offset(0.001));

        // And the two must still point in opposite directions.
        assertThat(avgRating(academySold))
                .as("Academy skims peaks, BuyTopSellWorst dumps dregs")
                .isGreaterThan(avgRating(worstSold));
    }

    /**
     * What the strategy *should* have listed: walk the source set in its stated
     * order, taking each player only while his base position still has surplus over
     * {@link TacticService#getMinimumPositionNeeded()} — the same capacity rule the
     * production loop applies, measured against the whole squad.
     */
    private double optimalSellSum(List<Human> source, Comparator<Human> order, int count) {
        Map<String, Integer> capacity = new HashMap<>();
        depthChart.squadCountsByBasePosition().forEach((position, players) ->
                capacity.put(position, Math.max(0, players - minPos.getOrDefault(position, 0))));

        double total = 0;
        int selected = 0;
        for (Human candidate : source.stream()
                .filter(player -> !player.isWillNeverLeave())
                .sorted(order)
                .toList()) {
            if (selected == count) break;
            String position = TacticService.getBasePosition(candidate.getPosition());
            int remaining = capacity.getOrDefault(position, 0);
            if (remaining == 0) continue;
            capacity.put(position, remaining - 1);
            total += candidate.getRating();
            selected++;
        }
        return total;
    }

    private static double ratingSum(List<PlayerTransferView> views) {
        return views.stream().mapToDouble(PlayerTransferView::getRating).sum();
    }

    @Test
    @DisplayName("SellHigh strategies list their most valuable starters, in descending value")
    void sellHighStrategiesSellTopValue() {
        long[] controlledSeeds = {0L, 1L, 2L, 3L, 4L, 42L, SEED, Long.MAX_VALUE};
        Set<Long> starterIds = depthChart.starterIds();

        for (long stratId : new long[]{
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH,
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH}) {
            for (long seed : controlledSeeds) {
                List<Long> soldIds = sell(stratId, seed).stream()
                        .map(PlayerTransferView::getPlayerId)
                        .toList();
                List<Long> repeatedIds = sell(stratId, seed).stream()
                        .map(PlayerTransferView::getPlayerId)
                        .toList();
                List<Long> soldValues = soldIds.stream()
                        .map(id -> squad.stream()
                                .filter(player -> player.getId() == id)
                                .findFirst()
                                .orElseThrow()
                                .getTransferValue())
                        .toList();

                assertThat(soldIds)
                        .as("strategy %d seed %d must pick players to sell", stratId, seed)
                        .isNotEmpty()
                        .doesNotHaveDuplicates();
                // BuyFreeSellHigh still skims the XI. BuyYoungSellHigh draws from the
                // whole squad: restricting it to starters made an expensive prospect on
                // the bench unsellable at any price, which is the opposite of cashing in
                // on value. Ordering by transfer value keeps the prime assets first
                // either way, so the sell-high character is unchanged.
                if (stratId == TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH) {
                    assertThat(soldIds)
                            .as("strategy %d seed %d sells high — from the XI, not the bench", stratId, seed)
                            .allMatch(starterIds::contains);
                }
                assertThat(repeatedIds)
                        .as("strategy %d seed %d must be reproducible", stratId, seed)
                        .containsExactlyElementsOf(soldIds);
                assertThat(soldValues)
                        .as("strategy %d seed %d must preserve descending transfer-value order", stratId, seed)
                        .isSortedAccordingTo(Comparator.reverseOrder());
                assertThat(soldValues.stream().mapToLong(Long::longValue).sum())
                        .as("strategy %d seed %d must maximize total feasible sale value", stratId, seed)
                        .isEqualTo(optimalSellHighValue(soldIds.size(),
                                stratId == TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH
                                        ? depthChart.starters() : depthChart.wholeSquad()));
                assertMinimumPositionCoverageAfterSales(soldIds, stratId, seed);
            }
        }
    }

    @Test
    @DisplayName("BuyMidSellMid draws at random from the WHOLE squad — its job is liquidity, not quality")
    void buyMidSellMidIsUnopinionated() {
        // It is the market's liquidity provider: it must be able to reach both the XI
        // and the bench, and over many draws its picks track the squad mean. That is a
        // statistical property, so it is asserted across seeds — a single seed says
        // nothing, which is exactly why the previous strict betweenness assertion held
        // only for the one seed it was written against.
        double squadAvgRating = squad.stream().mapToDouble(Human::getRating).average().orElseThrow();
        Set<Long> starterIds = depthChart.starterIds();

        int seeds = 40;
        double soldTotal = 0;
        int soldCount = 0;
        boolean touchedStarter = false;
        boolean touchedReserve = false;

        for (int seed = 0; seed < seeds; seed++) {
            List<PlayerTransferView> sold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, seed);
            assertThat(sold).as("seed %d must pick players to sell", seed).isNotEmpty();
            for (PlayerTransferView view : sold) {
                soldTotal += view.getRating();
                soldCount++;
                if (starterIds.contains(view.getPlayerId())) touchedStarter = true;
                else touchedReserve = true;
            }
        }

        assertThat(touchedStarter)
                .as("across %d seeds it must reach the XI at least once", seeds).isTrue();
        assertThat(touchedReserve)
                .as("across %d seeds it must reach the bench at least once", seeds).isTrue();
        assertThat(soldTotal / soldCount)
                .as("its aggregate pick should sit near the squad mean (%.1f)", squadAvgRating)
                .isCloseTo(squadAvgRating, org.assertj.core.data.Offset.offset(squadAvgRating * 0.20));
    }

    @Test
    @DisplayName("No strategy sells more than the surplus at any position (keeps the minimum)")
    void minimumPositionNeededIsNeverBreached() {
        Map<String, Integer> have = new HashMap<>();
        for (Human h : squad) have.merge(TacticService.getBasePosition(h.getPosition()), 1, Integer::sum);

        for (long stratId : new long[]{1L, 2L, 3L, 4L, 5L, 6L}) {
            Map<String, Integer> sold = new HashMap<>();
            for (PlayerTransferView v : sell(stratId)) sold.merge(v.getPosition(), 1, Integer::sum);

            for (Map.Entry<String, Integer> e : sold.entrySet()) {
                String pos = e.getKey();
                int min = minPos.getOrDefault(pos, 0);
                assertThat(e.getValue())
                        .as("strategy %d must not sell more than surplus at %s (have %d, min %d)",
                                stratId, pos, have.getOrDefault(pos, 0), min)
                        .isLessThanOrEqualTo(Math.max(0, have.getOrDefault(pos, 0) - min));
            }
        }
    }

    // ============================================================
    //  Buying intent
    // ============================================================

    @Test
    @DisplayName("Buy plans rank the weakest positions first and carry the club's own bar")
    void buyPlansAreWellFormed() {
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, 24);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, 40);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, 40);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, 40);
    }

    @Test
    @DisplayName("Academy buys — but only what costs nothing")
    void academyBuysFreeAgentsOnly() {
        // It used to return no plan at all, which removed a fifth of the market's
        // demand. Real academy clubs still sign released players to cover positions
        // the youth intake missed; they just never pay a fee.
        BuyPlanTransferView plan = buy(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY);

        assertThat(plan).as("Academy must take part in the window").isNotNull();
        assertThat(plan.getPositions())
                .as("Academy shops for 1-2 gaps, not a rebuild")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(2);
        assertThat(plan.getSpendingCap())
                .as("Academy may not spend a penny — free agents only")
                .isZero();
    }

    @Test
    @DisplayName("Strategies differ on how far below their bar they will sign squad depth")
    void stepUpGapsDifferentiateBuyers() {
        double top = buy(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST).getStepUpGap();
        double young = buy(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH).getStepUpGap();
        double free = buy(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH).getStepUpGap();
        double mid = buy(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID).getStepUpGap();

        // Before this change all four buy plans were byte-identical bar one max-age
        // constant, so "strategy" meant nothing on the buying side.
        assertThat(top)
                .as("the ambitious club wants upgrades, not filler — narrowest tolerance")
                .isLessThan(young).isLessThan(free).isLessThan(mid);
        assertThat(mid)
                .as("the liquidity provider says yes most often — widest tolerance")
                .isGreaterThan(free).isGreaterThan(young);
    }

    private void assertBuyPlan(long stratId, int expectedMaxAge) {
        BuyPlanTransferView plan = buy(stratId);
        assertThat(plan).as("strategy %d must produce a buy plan", stratId).isNotNull();
        assertThat(plan.getMaxAge()).as("strategy %d max age", stratId).isEqualTo(expectedMaxAge);
        assertThat(plan.getTeamId()).as("strategy %d buy plan team id", stratId).isEqualTo(team.getId());
        assertThat(plan.getXiAverage())
                .as("strategy %d must carry the club's own level", stratId)
                .isEqualTo(depthChart.xiAverage());

        assertThat(plan.getPositions().size())
                .as("strategy %d must request at most 4 positions", stratId)
                .isLessThanOrEqualTo(4);

        assertThat(plan.getPositions().stream().map(TransferPlayer::getDeficit).toList())
                .as("strategy %d must shop for its weakest positions first", stratId)
                .isSortedAccordingTo(Comparator.reverseOrder());

        for (TransferPlayer slot : plan.getPositions()) {
            String basePos = slot.getPosition();
            assertThat(depthChart.squadCount(basePos))
                    .as("strategy %d must only buy for a position under its roster cap %s (have %d, cap %d)",
                            stratId, basePos, depthChart.squadCount(basePos),
                            maxPos.getOrDefault(basePos, 0))
                    .isLessThan(maxPos.getOrDefault(basePos, Integer.MAX_VALUE));
            assertThat(slot.getIncumbentRating())
                    .as("strategy %d slot %s must carry the incumbent it has to beat", stratId, basePos)
                    .isEqualTo(depthChart.incumbentRating(basePos));
        }
    }

    // ============================================================
    //  Determinism + robustness
    // ============================================================

    @Test
    @DisplayName("Same seed → identical sell set and buy plan")
    void strategyOutputIsDeterministicUnderFixedSeed() {
        long stratId = TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH;

        List<Long> sellA = sell(stratId).stream().map(PlayerTransferView::getPlayerId).sorted().toList();
        List<String> buyA = buyPositions(stratId);

        List<Long> sellB = sell(stratId).stream().map(PlayerTransferView::getPlayerId).sorted().toList();
        List<String> buyB = buyPositions(stratId);

        assertThat(sellB).as("sell set must be reproducible under a fixed seed").isEqualTo(sellA);
        assertThat(buyB).as("buy plan must be reproducible under a fixed seed").isEqualTo(buyA);
    }

    @Test
    @DisplayName("Unmapped/null/zero strategy → no sales, no buy plan, no exception")
    void unmappedStrategyIsInert() {
        for (Long badStrategy : new Long[]{null, 0L, -1L, 999L}) {
            team.setStrategy(badStrategy);
            strategy.setRandomForTesting(new Random(SEED));

            assertThat(strategy.playersToSell(team, depthChart, minPos))
                    .as("strategy=%s must sell nobody", badStrategy)
                    .isEmpty();
            assertThat(strategy.playersToBuy(team, depthChart, maxPos))
                    .as("strategy=%s must have no buy plan", badStrategy)
                    .isNull();
        }
    }

    // ============================================================
    //  Descriptive reports (target/)
    // ============================================================

    @Test
    @DisplayName("Report: each strategy's sell source + drift → target/transfer-strategy-sell-direction.md")
    void emitSellDirectionReport() throws IOException {
        double squadAvgRating = squad.stream().mapToDouble(Human::getRating).average().orElseThrow();
        double squadAvgValue = squad.stream().mapToLong(Human::getTransferValue).average().orElseThrow();
        Map<Long, Long> valueById = new HashMap<>();
        for (Human h : squad) valueById.put(h.getId(), h.getTransferValue());
        Set<Long> starterIds = depthChart.starterIds();

        MarkdownTable table = new MarkdownTable(
                List.of("Strategy", "#Sold", "From XI", "Sold avg rating", "Δ vs squad rating",
                        "Sold avg value", "Δ vs squad value"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT));

        for (Map.Entry<Long, String> e : STRATEGY_NAME.entrySet()) {
            List<PlayerTransferView> sold = sell(e.getKey());
            double rAvg = sold.isEmpty() ? 0 : avgRating(sold);
            double vAvg = sold.isEmpty() ? 0 : sold.stream()
                    .mapToLong(v -> valueById.getOrDefault(v.getPlayerId(), 0L)).average().orElse(0);
            long fromXi = sold.stream().filter(v -> starterIds.contains(v.getPlayerId())).count();
            table.addRow(
                    e.getValue() + " (" + e.getKey() + ")",
                    String.valueOf(sold.size()),
                    fromXi + "/" + sold.size(),
                    String.format("%.1f", rAvg),
                    String.format("%+.1f", rAvg - squadAvgRating),
                    String.format("%,.0f", vAvg),
                    String.format("%+,.0f", vAvg - squadAvgValue));
        }

        StringBuilder md = new StringBuilder();
        md.append("# Transfer strategy sell-direction report\n\n");
        md.append("- seed: ").append(SEED).append('\n');
        md.append("- sample team: id=").append(team.getId())
                .append(" (").append(team.getName()).append("), squad=").append(squad.size())
                .append(", XI average=").append(String.format("%.1f", depthChart.xiAverage())).append('\n');
        md.append("- squad avg rating: ").append(String.format("%.1f", squadAvgRating))
                .append(", squad avg value: ").append(String.format("%,.0f", squadAvgValue)).append("\n\n");
        md.append("Each strategy is asked, on the *same* squad with the *same* seed, which players it\n");
        md.append("would put up for sale. \"From XI\" shows which set it drew from — the club's best\n");
        md.append("eleven or its reserves — which is what now distinguishes the strategies.\n\n");
        md.append(table.render());

        Path report = Path.of("target", "transfer-strategy-sell-direction.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString());
        System.out.println(md);

        assertThat(sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY)).isNotEmpty();
    }

    @Test
    @DisplayName("Diagnose & report why teams make NO transfers → target/transfer-no-trade-causes.md")
    void diagnoseNoTransferCauses() throws IOException {
        List<Team> allTeams = teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId)).toList();

        int s = 0;
        for (Team t : allTeams) t.setStrategy((long) ((s++ % 6) + 1));

        strategy.setRandomForTesting(new Random(SEED));
        Map<Long, TransferMarketDiagnostics.TeamIntent> intents = TransferMarketDiagnostics.snapshotTeamIntents(
                allTeams, strategy, humanRepository, tacticService,
                matchSimulationOrchestrator, matchEngineConfig);
        List<TransferMarketDiagnostics.TeamNoTransferDiagnostic> diagnostics =
                TransferMarketDiagnostics.classifyNoTransferTeams(intents, transferMarketService);

        Map<String, Integer> causeTally = new LinkedHashMap<>();
        Map<String, Integer> perStrategyTraders = new LinkedHashMap<>();
        Map<Long, TransferMarketDiagnostics.TeamNoTransferDiagnostic> diagnosticByTeam = new HashMap<>();
        for (TransferMarketDiagnostics.TeamNoTransferDiagnostic diagnostic : diagnostics) {
            diagnosticByTeam.put(diagnostic.intent().teamId(), diagnostic);
            causeTally.merge(diagnostic.cause().code(), 1, Integer::sum);
        }
        for (Team teamIt : allTeams) {
            if (!diagnosticByTeam.containsKey(teamIt.getId())) {
                perStrategyTraders.merge(
                        STRATEGY_NAME.getOrDefault(teamIt.getStrategy(), "Unmapped"), 1, Integer::sum);
            }
        }

        MarkdownTable byCause = new MarkdownTable(
                List.of("No-transfer cause", "Teams"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        causeTally.forEach((c, n) -> byCause.addRow(c, String.valueOf(n)));

        MarkdownTable byStrat = new MarkdownTable(
                List.of("Strategy", "Teams with a live opportunity"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        STRATEGY_NAME.values().forEach(n ->
                byStrat.addRow(n, String.valueOf(perStrategyTraders.getOrDefault(n, 0))));

        MarkdownTable detail = new MarkdownTable(
                List.of("Team", "Strategy", "Cause", "#Sell", "#Buy", "XI avg", "Budget blocked"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.LEFT));
        diagnostics.forEach(diagnostic -> detail.addRow(
                diagnostic.intent().teamName(),
                STRATEGY_NAME.getOrDefault(diagnostic.intent().strategyId(), "Unmapped"),
                diagnostic.cause().code(),
                String.valueOf(diagnostic.intent().sellCandidates().size()),
                String.valueOf(diagnostic.intent().buyPlan() == null || diagnostic.intent().buyPlan().getPositions() == null
                        ? 0
                        : diagnostic.intent().buyPlan().getPositions().size()),
                String.format("%.1f", diagnostic.intent().depthChart().xiAverage()),
                diagnostic.budgetBlocked() ? "yes" : "no"));

        StringBuilder md = new StringBuilder();
        md.append("# No-transfer diagnostic (deterministic, pre-pipeline)\n\n");
        md.append("- seed: ").append(SEED).append('\n');
        md.append("- teams analysed: ").append(allTeams.size())
                .append(" (strategies round-robined 1..5)\n\n");
        md.append("Classification mirrors `EndOfSeasonProcessor`'s gate order: strategy intent →\n");
        md.append("`TransferMarketService.canBeTransfered` (age/position/incumbent/step-up) → budget.\n");
        md.append("A team is flagged only if it has NO actual transfer match on either side of the\n");
        md.append("market; just listing a seller does not count as a live opportunity.\n\n");
        md.append("## Why teams cannot trade\n\n").append(byCause.render());
        md.append("\n## Live opportunities by strategy\n\n").append(byStrat.render());
        if (!diagnostics.isEmpty()) {
            md.append("\n## Flagged teams\n\n").append(detail.render());
        }

        Path report = Path.of("target", "transfer-no-trade-causes.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString());
        System.out.println(md);

        int flagged = causeTally.values().stream().mapToInt(Integer::intValue).sum();
        int traders = perStrategyTraders.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(flagged + traders)
                .as("every team must be classified as either a trader or a single no-trade cause")
                .isEqualTo(allTeams.size());
    }

    // ============================================================
    //  helpers — always re-seed so each strategy draws the same counts
    // ============================================================

    private static double avgRating(List<PlayerTransferView> views) {
        return views.stream().mapToDouble(PlayerTransferView::getRating).average().orElse(0);
    }

    private List<PlayerTransferView> sell(long stratId) {
        return sell(stratId, SEED);
    }

    private List<PlayerTransferView> sell(long stratId, long seed) {
        team.setStrategy(stratId);
        strategy.setRandomForTesting(new Random(seed));
        return strategy.playersToSell(team, depthChart, minPos);
    }

    /**
     * Independent oracle for the SellHigh contract. Candidates are the club's
     * starters (that is the set the strategy draws from); a position may contribute
     * at most {@code squadCount - minimumRequired} sale slots. Greedily taking the
     * highest-value legal starter is optimal for these independent per-position
     * capacities; protected players are never candidates.
     */
    private long optimalSellHighValue(int count, List<Human> pool) {
        Map<String, Integer> saleCapacity = new HashMap<>();
        depthChart.squadCountsByBasePosition().forEach((position, players) -> saleCapacity.put(
                position,
                Math.max(0, players - minPos.getOrDefault(position, 0))));

        List<Human> candidates = pool.stream()
                .filter(player -> !player.isWillNeverLeave())
                .sorted(Comparator.comparingLong(Human::getTransferValue).reversed())
                .collect(Collectors.toList());

        long optimalValue = 0;
        int selected = 0;
        for (Human candidate : candidates) {
            String position = TacticService.getBasePosition(candidate.getPosition());
            int remainingCapacity = saleCapacity.getOrDefault(position, 0);
            if (remainingCapacity == 0) continue;
            optimalValue += candidate.getTransferValue();
            selected++;
            saleCapacity.put(position, remainingCapacity - 1);
            if (selected == count) break;
        }
        return optimalValue;
    }

    private void assertMinimumPositionCoverageAfterSales(List<Long> soldIds, long stratId, long seed) {
        Map<String, Integer> remaining = new HashMap<>();
        for (Human player : squad) {
            if (!soldIds.contains(player.getId())) {
                remaining.merge(TacticService.getBasePosition(player.getPosition()), 1, Integer::sum);
            }
        }
        minPos.forEach((position, minimum) -> assertThat(remaining.getOrDefault(position, 0))
                .as("strategy %d seed %d must retain minimum coverage for %s", stratId, seed, position)
                .isGreaterThanOrEqualTo(minimum));
    }

    private BuyPlanTransferView buy(long stratId) {
        team.setStrategy(stratId);
        strategy.setRandomForTesting(new Random(SEED));
        return strategy.playersToBuy(team, depthChart, maxPos);
    }

    private List<String> buyPositions(long stratId) {
        BuyPlanTransferView plan = buy(stratId);
        return plan == null ? List.of()
                : plan.getPositions().stream().map(TransferPlayer::getPosition).sorted().toList();
    }
}
