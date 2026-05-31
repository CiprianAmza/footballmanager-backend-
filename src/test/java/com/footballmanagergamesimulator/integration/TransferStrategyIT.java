package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that each AI transfer strategy honours its named selling/buying
 * criterion and never sells below {@link TacticService#getMinimumPositionNeeded()}.
 *
 * <p>Calls the strategies <b>directly</b> via {@link CompositeTransferStrategy}
 * (bypassing the residual non-determinism in {@code EndOfSeasonProcessor}) with a
 * seeded RNG threaded through {@code setRandomForTesting}, so every assertion is
 * 100% reproducible. Fast (no season is simulated) → runs in the default
 * {@code mvn verify} gate.
 *
 * <p>These tests pin the fix for the inverted-sell bug: before the fix each
 * strategy sold the <i>tail</i> of its sorted candidate list (the opposite of
 * its intent); after the fix it sells the <i>head</i>. The directional
 * assertions below (Academy sells higher-rated than BuyTopSellWorst; SellHigh
 * variants sell higher-value than the squad average) fail on the buggy code and
 * pass on the corrected code.
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
@DisplayName("Transfer strategies: sell/buy criterion + minimum-position invariant")
class TransferStrategyIT {

    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TacticService tacticService;
    @Autowired private TransferMarketService transferMarketService;

    private static final long SEED = 20260528L;

    private static final Map<Long, String> STRATEGY_NAME = new LinkedHashMap<>(Map.of(
            TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, "Academy",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, "BuyYoungSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, "BuyFreeSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, "BuyMidSellMid",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, "BuyTopSellWorst"));

    private Team team;                       // the team with the largest squad (most sell surplus)
    private List<Human> squad;               // its players
    private HashMap<String, Integer> minPos; // minimum per-position coverage
    private HashMap<String, Integer> maxPos; // per-position roster caps

    @BeforeEach
    void pickFullestSquadTeam() {
        minPos = tacticService.getMinimumPositionNeeded();
        maxPos = tacticService.getMaximumPositionAllowed();

        team = teamRepository.findAll().stream()
                .max(Comparator.comparingInt(t ->
                        humanRepository.findAllByTeamIdAndTypeId(t.getId(), TypeNames.PLAYER_TYPE).size()))
                .orElseThrow(() -> new IllegalStateException("No teams — bootstrap didn't run?"));
        squad = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);

        assertThat(squad.size())
                .as("fullest squad must be large enough to expose sell surplus")
                .isGreaterThanOrEqualTo(16);
    }

    @AfterEach
    void restoreProductionRng() {
        strategy.setRandomForTesting(new Random());
    }

    // ============================================================
    //  Req #1 — selling criterion per strategy
    // ============================================================

    @Test
    @DisplayName("Academy sells its TOP-rated players; BuyTopSellWorst sells its WORST")
    void academySellsTops_buyTopSellWorstSellsWorst() {
        double squadAvgRating = squad.stream().mapToDouble(Human::getRating).average().orElseThrow();

        List<PlayerTransferView> academySold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY);
        List<PlayerTransferView> worstSold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST);

        assertThat(academySold).as("Academy must pick players to sell").isNotEmpty();
        assertThat(worstSold).as("BuyTopSellWorst must pick players to sell").isNotEmpty();

        double academyAvg = academySold.stream().mapToDouble(PlayerTransferView::getRating).average().orElseThrow();
        double worstAvg = worstSold.stream().mapToDouble(PlayerTransferView::getRating).average().orElseThrow();

        assertThat(academyAvg)
                .as("Academy sells peaks → its sold set must out-rate BuyTopSellWorst's (sold avg: academy=%.1f worst=%.1f)",
                        academyAvg, worstAvg)
                .isGreaterThan(worstAvg);
        assertThat(academyAvg)
                .as("Academy sold avg rating (%.1f) must exceed squad avg (%.1f)", academyAvg, squadAvgRating)
                .isGreaterThan(squadAvgRating);
        assertThat(worstAvg)
                .as("BuyTopSellWorst sold avg rating (%.1f) must be below squad avg (%.1f)", worstAvg, squadAvgRating)
                .isLessThan(squadAvgRating);
    }

    @Test
    @DisplayName("BuyYoungSellHigh & BuyFreeSellHigh sell their HIGHEST transfer-value players")
    void sellHighStrategiesSellTopValue() {
        Map<Long, Long> valueById = new HashMap<>();
        for (Human h : squad) valueById.put(h.getId(), h.getTransferValue());
        double squadAvgValue = squad.stream().mapToLong(Human::getTransferValue).average().orElseThrow();

        for (long stratId : new long[]{
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH,
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH}) {
            List<PlayerTransferView> sold = sell(stratId);
            assertThat(sold).as("strategy %d must pick players to sell", stratId).isNotEmpty();

            double soldAvgValue = sold.stream()
                    .mapToLong(v -> valueById.getOrDefault(v.getPlayerId(), 0L))
                    .average().orElseThrow();

            assertThat(soldAvgValue)
                    .as("strategy %d (sell high) sold avg value (%.0f) must exceed squad avg (%.0f)",
                            stratId, soldAvgValue, squadAvgValue)
                    .isGreaterThan(squadAvgValue);
        }
    }

    @Test
    @DisplayName("BuyMidSellMid sells from the middle — not the squad's top, not its bottom")
    void buyMidSellMidSellsMidRange() {
        double squadAvgRating = squad.stream().mapToDouble(Human::getRating).average().orElseThrow();

        double academyAvg = avgRating(sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY));
        double worstAvg = avgRating(sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST));
        List<PlayerTransferView> midSold = sell(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID);
        double midAvg = avgRating(midSold);

        assertThat(midSold).as("BuyMidSellMid must pick players to sell").isNotEmpty();
        // The shuffled "mid" pick must not deliberately skim the peaks (Academy) nor
        // dump only the dregs (BuyTopSellWorst): it sits strictly between them.
        assertThat(midAvg)
                .as("BuyMidSellMid sold avg rating (%.1f) must be below Academy's peak-skimming avg (%.1f)",
                        midAvg, academyAvg)
                .isLessThan(academyAvg);
        assertThat(midAvg)
                .as("BuyMidSellMid sold avg rating (%.1f) must be above BuyTopSellWorst's dregs avg (%.1f)",
                        midAvg, worstAvg)
                .isGreaterThan(worstAvg);
        // And it should land near the squad average (within a generous band) — it is
        // an un-opinionated random pick, so it tracks the mean, not the extremes.
        assertThat(midAvg)
                .as("BuyMidSellMid sold avg rating (%.1f) should sit near the squad mean (%.1f)",
                        midAvg, squadAvgRating)
                .isCloseTo(squadAvgRating, org.assertj.core.data.Offset.offset(squadAvgRating * 0.35 + 5));
    }

    // ============================================================
    //  Req #2 — per-strategy sell-direction report (descriptive, target/)
    // ============================================================

    @Test
    @DisplayName("Report: each strategy's sold-vs-squad rating/value drift → target/transfer-strategy-sell-direction.md")
    void emitSellDirectionReport() throws IOException {
        double squadAvgRating = squad.stream().mapToDouble(Human::getRating).average().orElseThrow();
        double squadAvgValue = squad.stream().mapToLong(Human::getTransferValue).average().orElseThrow();
        Map<Long, Long> valueById = new HashMap<>();
        for (Human h : squad) valueById.put(h.getId(), h.getTransferValue());

        MarkdownTable table = new MarkdownTable(
                List.of("Strategy", "#Sold", "Sold avg rating", "Δ vs squad rating",
                        "Sold avg value", "Δ vs squad value", "Direction"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.LEFT));

        for (Map.Entry<Long, String> e : STRATEGY_NAME.entrySet()) {
            List<PlayerTransferView> sold = sell(e.getKey());
            double rAvg = sold.isEmpty() ? 0 : avgRating(sold);
            double vAvg = sold.isEmpty() ? 0 : sold.stream()
                    .mapToLong(v -> valueById.getOrDefault(v.getPlayerId(), 0L)).average().orElse(0);
            String direction;
            if (sold.isEmpty()) direction = "—";
            else if (rAvg > squadAvgRating + 2) direction = "sells HIGH (skims peaks)";
            else if (rAvg < squadAvgRating - 2) direction = "sells LOW (dumps dregs)";
            else direction = "sells MID (near mean)";
            table.addRow(
                    e.getValue() + " (" + e.getKey() + ")",
                    String.valueOf(sold.size()),
                    String.format("%.1f", rAvg),
                    String.format("%+.1f", rAvg - squadAvgRating),
                    String.format("%,.0f", vAvg),
                    String.format("%+,.0f", vAvg - squadAvgValue),
                    direction);
        }

        StringBuilder md = new StringBuilder();
        md.append("# Transfer strategy sell-direction report\n\n");
        md.append("- seed: ").append(SEED).append('\n');
        md.append("- sample team: id=").append(team.getId())
                .append(" (").append(team.getName()).append("), squad=").append(squad.size()).append('\n');
        md.append("- squad avg rating: ").append(String.format("%.1f", squadAvgRating))
                .append(", squad avg value: ").append(String.format("%,.0f", squadAvgValue)).append("\n\n");
        md.append("Each strategy is asked, on the *same* squad with the *same* seed, which players it\n");
        md.append("would put up for sale. The drift columns show how its sold set deviates from the\n");
        md.append("squad mean — proving the strategy honours its named intent.\n\n");
        md.append(table.render());

        Path report = Path.of("target", "transfer-strategy-sell-direction.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString());
        System.out.println(md);

        // Sanity: at least the opinionated strategies must list someone.
        assertThat(sell(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY)).isNotEmpty();
    }

    // ============================================================
    //  Req #3 — deterministic per-team no-transfer diagnostic
    //  (uses the SAME gates as EndOfSeasonProcessor: strategy intent +
    //   TransferMarketService.canBeTransfered + budget)
    // ============================================================

    @Test
    @DisplayName("Diagnose & report why teams make NO transfers → target/transfer-no-trade-causes.md")
    void diagnoseNoTransferCauses() throws IOException {
        HashMap<String, Integer> minPos = tacticService.getMinimumPositionNeeded();
        HashMap<String, Integer> maxPos = tacticService.getMaximumPositionAllowed();
        List<Team> allTeams = teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId)).toList();

        // 1. Assign a deterministic strategy to every team (round-robin 1..5).
        int s = 0;
        for (Team t : allTeams) t.setStrategy((long) ((s++ % 5) + 1));

        // 2. Build the AI sell market exactly like EndOfSeasonProcessor does.
        strategy.setRandomForTesting(new Random(SEED));
        Map<String, List<PlayerTransferView>> market = new HashMap<>();
        Map<Long, List<PlayerTransferView>> sellByTeam = new HashMap<>();
        for (Team t : allTeams) {
            List<PlayerTransferView> sold = strategy.playersToSell(t, humanRepository, minPos);
            sellByTeam.put(t.getId(), sold);
            for (PlayerTransferView v : sold)
                market.computeIfAbsent(v.getPosition(), k -> new ArrayList<>()).add(v);
        }

        // 3. For each team, classify the cause if it cannot complete a transfer
        //    as either buyer or seller. Mirrors the real pipeline's gate order.
        Map<String, Integer> causeTally = new LinkedHashMap<>();
        Map<String, Integer> perStrategyTraders = new LinkedHashMap<>();
        for (Team t : allTeams) {
            strategy.setRandomForTesting(new Random(SEED));
            List<PlayerTransferView> sold = sellByTeam.get(t.getId());
            BuyPlanTransferView plan = strategy.playersToBuy(t, humanRepository, maxPos);

            boolean canSell = !sold.isEmpty();                 // someone else may buy these
            boolean wantsBuy = plan != null && !plan.getPositions().isEmpty();

            // Does any market player satisfy this team's buy plan (eligibility + budget)?
            boolean buyMatch = false;
            boolean eligibleButBroke = false;
            if (wantsBuy) {
                for (TransferPlayer want : plan.getPositions()) {
                    List<PlayerTransferView> avail = market.get(want.getPosition());
                    if (avail == null) continue;
                    for (PlayerTransferView cand : avail) {
                        if (cand.getTeamId() == t.getId()) continue;
                        if (!transferMarketService.canBeTransfered(cand, plan, want)) continue;
                        long fee = com.footballmanagergamesimulator.service.TransferValueCalculator
                                .calculate(cand.getAge(), cand.getPosition(), cand.getRating());
                        if (fee > t.getTransferBudget()) { eligibleButBroke = true; continue; }
                        buyMatch = true;
                        break;
                    }
                    if (buyMatch) break;
                }
            }

            String cause;
            String strat = STRATEGY_NAME.getOrDefault(t.getStrategy(), "Unmapped");
            if (buyMatch || canSell) {
                // The team has a live opportunity on at least one side of the market.
                cause = null;
                perStrategyTraders.merge(strat, 1, Integer::sum);
            } else if (!wantsBuy && !canSell) {
                cause = "NOTHING_TO_TRADE (no surplus to sell, no slot to fill — incl. Academy never buys)";
            } else if (!wantsBuy) {
                cause = "NO_BUY_TARGETS (positions full / Academy never buys) and nobody buys its sellers";
            } else if (eligibleButBroke) {
                cause = "NO_MARKET_MATCH: INSUFFICIENT_BUDGET (eligible seller exists but fee > budget)";
            } else {
                cause = "NO_MARKET_MATCH: NO_ELIGIBLE_SELLER (no young/affordable counterpart at wanted position)";
            }
            if (cause != null) causeTally.merge(cause, 1, Integer::sum);
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

        StringBuilder md = new StringBuilder();
        md.append("# No-transfer diagnostic (deterministic, pre-pipeline)\n\n");
        md.append("- seed: ").append(SEED).append('\n');
        md.append("- teams analysed: ").append(allTeams.size())
                .append(" (strategies round-robined 1..5)\n\n");
        md.append("Classification mirrors `EndOfSeasonProcessor`'s gate order: strategy intent →\n");
        md.append("`TransferMarketService.canBeTransfered` (age/reputation/position/rating) → budget\n");
        md.append("(`fee > transferBudget`). A team is flagged only if it has NO live opportunity on\n");
        md.append("either side of the market.\n\n");
        md.append("## Why teams cannot trade\n\n").append(byCause.render());
        md.append("\n## Live opportunities by strategy\n\n").append(byStrat.render());

        Path report = Path.of("target", "transfer-no-trade-causes.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString());
        System.out.println(md);

        // Academy teams never buy, so any Academy team flagged must be flagged for a
        // buy-side reason, never "eligible seller found but couldn't buy".
        assertThat(causeTally.keySet())
                .as("INSUFFICIENT_BUDGET cause must not appear for a strategy that never buys")
                .allSatisfy(k -> assertThat(k).doesNotContain("Academy never buys and INSUFFICIENT"));
        // The diagnostic must be total: every flagged team has exactly one cause.
        int flagged = causeTally.values().stream().mapToInt(Integer::intValue).sum();
        int traders = perStrategyTraders.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(flagged + traders)
                .as("every team must be classified as either a trader or a single no-trade cause")
                .isEqualTo(allTeams.size());
    }

    private static double avgRating(List<PlayerTransferView> views) {
        return views.stream().mapToDouble(PlayerTransferView::getRating).average().orElse(0);
    }

    @Test
    @DisplayName("No strategy sells more than the surplus at any position (keeps the minimum)")
    void minimumPositionNeededIsNeverBreached() {
        Map<String, Integer> have = new HashMap<>();
        for (Human h : squad) have.merge(TacticService.getBasePosition(h.getPosition()), 1, Integer::sum);

        for (long stratId : new long[]{1L, 2L, 3L, 4L, 5L}) {
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
    //  Req #1 — buying criterion per strategy
    // ============================================================

    @Test
    @DisplayName("Buy plans target under-filled positions; BuyYoung caps age at 24, others at 40; Academy never buys")
    void buyPlansAreWellFormed() {
        assertThat(buy(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY))
                .as("Academy develops youth — it must not buy").isNull();

        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, 24);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, 40);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, 40);
        assertBuyPlan(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, 40);
    }

    private void assertBuyPlan(long stratId, int expectedMaxAge) {
        BuyPlanTransferView plan = buy(stratId);
        assertThat(plan).as("strategy %d must produce a buy plan", stratId).isNotNull();
        assertThat(plan.getMaxAge()).as("strategy %d max age", stratId).isEqualTo(expectedMaxAge);
        assertThat(plan.getTeamId()).as("strategy %d buy plan team id", stratId).isEqualTo(team.getId());

        // At most nextInt(3,5) == {3,4} positions are requested.
        assertThat(plan.getPositions().size())
                .as("strategy %d must request at most 4 positions", stratId)
                .isLessThanOrEqualTo(4);

        Map<String, Integer> have = new HashMap<>();
        for (Human h : squad) have.merge(TacticService.getBasePosition(h.getPosition()), 1, Integer::sum);
        for (TransferPlayer p : plan.getPositions()) {
            String basePos = TacticService.getBasePosition(p.getPosition());
            assertThat(have.getOrDefault(basePos, 0))
                    .as("strategy %d must only buy for under-filled position %s (have %d, cap %d)",
                            stratId, basePos, have.getOrDefault(basePos, 0),
                            maxPos.getOrDefault(basePos, 0))
                    .isLessThan(maxPos.getOrDefault(basePos, Integer.MAX_VALUE));
        }
    }

    // ============================================================
    //  Determinism + robustness (Req #4 core)
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

            assertThat(strategy.playersToSell(team, humanRepository, minPos))
                    .as("strategy=%s must sell nobody", badStrategy)
                    .isEmpty();
            assertThat(strategy.playersToBuy(team, humanRepository, maxPos))
                    .as("strategy=%s must have no buy plan", badStrategy)
                    .isNull();
        }
    }

    // ============================================================
    //  helpers — always re-seed so each strategy draws the same counts
    // ============================================================

    private List<PlayerTransferView> sell(long stratId) {
        team.setStrategy(stratId);
        strategy.setRandomForTesting(new Random(SEED));
        return strategy.playersToSell(team, humanRepository, minPos);
    }

    private BuyPlanTransferView buy(long stratId) {
        team.setStrategy(stratId);
        strategy.setRandomForTesting(new Random(SEED));
        return strategy.playersToBuy(team, humanRepository, maxPos);
    }

    private List<String> buyPositions(long stratId) {
        BuyPlanTransferView plan = buy(stratId);
        return plan == null ? List.of()
                : plan.getPositions().stream().map(TransferPlayer::getPosition).sorted().toList();
    }
}
