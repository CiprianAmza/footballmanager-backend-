package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
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

import java.util.Comparator;
import java.util.HashMap;
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
@DisplayName("Transfer strategies: sell/buy criterion + minimum-position invariant")
class TransferStrategyIT {

    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TacticService tacticService;

    private static final long SEED = 20260528L;

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
    @DisplayName("No strategy sells more than the surplus at any position (keeps the minimum)")
    void minimumPositionNeededIsNeverBreached() {
        Map<String, Integer> have = new HashMap<>();
        for (Human h : squad) have.merge(h.getPosition(), 1, Integer::sum);

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
        for (Human h : squad) have.merge(h.getPosition(), 1, Integer::sum);
        for (TransferPlayer p : plan.getPositions()) {
            assertThat(have.getOrDefault(p.getPosition(), 0))
                    .as("strategy %d must only buy for under-filled position %s (have %d, cap %d)",
                            stratId, p.getPosition(), have.getOrDefault(p.getPosition(), 0),
                            maxPos.getOrDefault(p.getPosition(), 0))
                    .isLessThan(maxPos.getOrDefault(p.getPosition(), Integer.MAX_VALUE));
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
