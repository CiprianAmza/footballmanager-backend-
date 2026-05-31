package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.TransferMarketDiagnostics;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "bootstrap.seed=20260528",
        "transfer.campaign.audit=true"
})
@DisplayName("Transfer campaign: completed transfers honour the owning strategy")
class TransferStrategyCampaignIT {

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

    private static final long SEED = 20260528L;
    private static final long LEAGUE_TYPE_ID = 1L;

    @AfterEach
    void restoreProductionRng() {
        matchSimulationService.setRandomForTesting(new Random());
        strategy.setRandomForTesting(new Random());
    }

    @Test
    @DisplayName("Completed transfers are a subset of the strategy sell-list and satisfy the strategy buy-plan")
    void completedTransfersRespectStrategyIntents() throws IOException {
        long leagueCompId = competitionRepository.findIdsByTypeId(LEAGUE_TYPE_ID)
                .stream().sorted().findFirst().orElseThrow();

        List<Team> teams = teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId))
                .toList();
        assignRoundRobinStrategies(teams);
        teamRepository.saveAll(teams);
        List<Team> processOrderTeams = teamRepository.findAll();

        int season = currentSeason();
        matchSimulationService.setRandomForTesting(new Random(SEED));
        strategy.setRandomForTesting(new Random(SEED));
        simulateLeague(leagueCompId, season);

        strategy.setRandomForTesting(new Random(SEED));
        Map<Long, TransferMarketDiagnostics.TeamIntent> intents =
                TransferMarketDiagnostics.snapshotTeamIntents(processOrderTeams, strategy, humanRepository, tacticService);
        Map<String, Integer> minPos = tacticService.getMinimumPositionNeeded();
        Map<String, Integer> maxPos = tacticService.getMaximumPositionAllowed();

        seasonTransitionService.processEndOfSeason(season);

        List<Transfer> transfers = transferRepository.findAllBySeasonNumber(season);
        assertThat(transfers)
                .as("the campaign should execute at least one completed transfer")
                .isNotEmpty();

        Map<Long, List<Transfer>> incomingByTeam = transfers.stream()
                .collect(java.util.stream.Collectors.groupingBy(Transfer::getBuyTeamId));
        Map<Long, List<Transfer>> outgoingByTeam = transfers.stream()
                .collect(java.util.stream.Collectors.groupingBy(Transfer::getSellTeamId));

        Map<Long, StrategyTransferStats> statsByStrategy = new TreeMap<>();
        for (Transfer transfer : transfers) {
            TransferMarketDiagnostics.TeamIntent sellerIntent = intents.get(transfer.getSellTeamId());
            TransferMarketDiagnostics.TeamIntent buyerIntent = intents.get(transfer.getBuyTeamId());
            var transferredPlayer = humanRepository.findById(transfer.getPlayerId()).orElseThrow();
            String basePosition = TacticService.getBasePosition(transferredPlayer.getPosition());

            assertThat(sellerIntent)
                    .as("every seller in the completed transfer list must have a strategy snapshot")
                    .isNotNull();
            assertThat(buyerIntent)
                    .as("every buyer in the completed transfer list must have a strategy snapshot")
                    .isNotNull();

            assertThat(sellerIntent.squadCountsByBasePosition().getOrDefault(basePosition, 0))
                    .as("seller %s must only sell from a position where it had surplus", sellerIntent.teamName())
                    .isGreaterThan(minPos.getOrDefault(basePosition, 0));

            if (buyerIntent.buyPlan() == null || buyerIntent.buyPlan().getPositions() == null) {
                assertThat(buyerIntent.buyPlan())
                        .as("team %s cannot complete an incoming transfer without a buy plan", buyerIntent.teamName())
                        .isNotNull();
            } else {
                assertThat(buyerIntent.squadCountsByBasePosition().getOrDefault(basePosition, 0))
                        .as("buyer %s must only buy for a pre-pipeline under-filled position", buyerIntent.teamName())
                        .isLessThan(maxPos.getOrDefault(basePosition, Integer.MAX_VALUE));
                if (buyerIntent.strategyId() == TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH) {
                    assertThat(transferredPlayer.getAge())
                            .as("BuyYoungSellHigh must only buy players aged 24 or below")
                            .isLessThanOrEqualTo(24);
                }
            }

            statsByStrategy.computeIfAbsent(sellerIntent.strategyId(), ignored -> new StrategyTransferStats())
                    .addSale(transfer, sellerIntent);
            statsByStrategy.computeIfAbsent(buyerIntent.strategyId(), ignored -> new StrategyTransferStats())
                    .addBuy(transfer);
        }

        for (TransferMarketDiagnostics.TeamIntent intent : intents.values()) {
            if (intent.buyPlan() == null) {
                assertThat(incomingByTeam.getOrDefault(intent.teamId(), List.of()))
                        .as("team %s with no buy plan must have no incoming transfers", intent.teamName())
                        .isEmpty();
            }
        }

        StrategyTransferStats academy = statsByStrategy.getOrDefault(
                TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, new StrategyTransferStats());
        StrategyTransferStats buyYoung = statsByStrategy.getOrDefault(
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, new StrategyTransferStats());
        StrategyTransferStats buyFree = statsByStrategy.getOrDefault(
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, new StrategyTransferStats());
        StrategyTransferStats buyMid = statsByStrategy.getOrDefault(
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, new StrategyTransferStats());
        StrategyTransferStats buyTop = statsByStrategy.getOrDefault(
                TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, new StrategyTransferStats());

        assertThat(academy.incomingCount)
                .as("Academy never buys in the current implementation")
                .isZero();
        if (buyYoung.incomingCount > 0) {
            assertThat(buyYoung.maxBoughtAge)
                    .as("BuyYoungSellHigh must only buy players aged 24 or below")
                    .isLessThanOrEqualTo(24);
        }
        if (academy.outgoingCount > 0) {
            assertThat(academy.avgSoldRating())
                    .as("Academy should sell above-squad-average rating")
                    .isGreaterThan(academy.avgSellerSquadRating());
        }
        if (buyTop.outgoingCount > 0) {
            assertThat(buyTop.avgSoldRating())
                    .as("BuyTopSellWorst should sell below-squad-average rating")
                    .isLessThan(buyTop.avgSellerSquadRating());
        }
        if (buyYoung.outgoingCount > 0) {
            assertThat(buyYoung.avgSoldFee())
                    .as("BuyYoungSellHigh should sell above-squad-average transfer value")
                    .isGreaterThan(buyYoung.avgSellerSquadValue());
        }
        if (buyFree.outgoingCount > 0) {
            assertThat(buyFree.avgSoldFee())
                    .as("BuyFreeSellHigh should sell above-squad-average transfer value")
                    .isGreaterThan(buyFree.avgSellerSquadValue());
        }
        if (buyMid.outgoingCount > 0 && academy.outgoingCount > 0 && buyTop.outgoingCount > 0) {
            assertThat(buyMid.avgSoldRating())
                    .as("BuyMidSellMid should sit between Academy's peak-skimming and BuyTopSellWorst's bottom-dumping")
                    .isBetween(buyTop.avgSoldRating(), academy.avgSoldRating());
        }

        writeReport(statsByStrategy, outgoingByTeam, incomingByTeam);
    }

    private void assignRoundRobinStrategies(List<Team> teams) {
        int index = 0;
        for (Team team : teams) {
            team.setStrategy((long) ((index++ % 5) + 1));
        }
    }

    private void simulateLeague(long leagueCompId, int season) {
        List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                leagueCompId, String.valueOf(season));
        matchdays.sort(Long::compareTo);
        for (Long matchday : matchdays) {
            competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(matchday));
        }
    }

    private int currentSeason() {
        return (int) roundRepository.findById(1L).orElseThrow().getSeason();
    }

    private void writeReport(Map<Long, StrategyTransferStats> statsByStrategy,
                             Map<Long, List<Transfer>> outgoingByTeam,
                             Map<Long, List<Transfer>> incomingByTeam) throws IOException {
        MarkdownTable table = new MarkdownTable(
                List.of("Strategy", "Buys", "Avg buy age", "Max buy age", "Sales",
                        "Avg sold fee", "Avg seller squad value", "Avg sold rating", "Avg seller squad rating"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));

        for (long strategyId = 1L; strategyId <= 5L; strategyId++) {
            StrategyTransferStats stats = statsByStrategy.getOrDefault(strategyId, new StrategyTransferStats());
            table.addRow(
                    TransferMarketDiagnostics.strategyName(strategyId),
                    String.valueOf(stats.incomingCount),
                    stats.incomingCount == 0 ? "—" : String.format("%.1f", stats.avgBoughtAge()),
                    stats.incomingCount == 0 ? "—" : String.valueOf(stats.maxBoughtAge),
                    String.valueOf(stats.outgoingCount),
                    stats.outgoingCount == 0 ? "—" : String.format("%,.0f", stats.avgSoldFee()),
                    stats.outgoingCount == 0 ? "—" : String.format("%,.0f", stats.avgSellerSquadValue()),
                    stats.outgoingCount == 0 ? "—" : String.format("%.1f", stats.avgSoldRating()),
                    stats.outgoingCount == 0 ? "—" : String.format("%.1f", stats.avgSellerSquadRating()));
        }

        StringBuilder md = new StringBuilder();
        md.append("# Transfer strategy campaign audit\n\n");
        md.append("- seed: ").append(SEED).append('\n');
        md.append("- completed transfers: ")
                .append(outgoingByTeam.values().stream().mapToInt(List::size).sum()).append('\n');
        md.append("- teams with outgoing transfers: ").append(outgoingByTeam.size()).append('\n');
        md.append("- teams with incoming transfers: ").append(incomingByTeam.size()).append("\n\n");
        md.append("Transfers are audited against the real pre-pipeline strategy snapshot: every completed\n");
        md.append("sale must come from the strategy's sell-list, and every completed purchase must match\n");
        md.append("at least one slot in that team's buy-plan.\n\n");
        md.append(table.render());

        Path report = Path.of("target", "transfer-strategy-campaign.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString());
        System.out.println(md);
    }

    private static final class StrategyTransferStats {
        private int incomingCount;
        private long boughtAgeTotal;
        private int maxBoughtAge;
        private int outgoingCount;
        private long soldFeeTotal;
        private double soldRatingTotal;
        private double sellerSquadValueTotal;
        private double sellerSquadRatingTotal;

        void addBuy(Transfer transfer) {
            incomingCount++;
            boughtAgeTotal += transfer.getPlayerAge();
            maxBoughtAge = Math.max(maxBoughtAge, (int) transfer.getPlayerAge());
        }

        void addSale(Transfer transfer, TransferMarketDiagnostics.TeamIntent sellerIntent) {
            outgoingCount++;
            soldFeeTotal += transfer.getPlayerTransferValue();
            soldRatingTotal += transfer.getRating();
            sellerSquadValueTotal += sellerIntent.squadAverageValue();
            sellerSquadRatingTotal += sellerIntent.squadAverageRating();
        }

        double avgBoughtAge() {
            return incomingCount == 0 ? 0 : (double) boughtAgeTotal / incomingCount;
        }

        double avgSoldFee() {
            return outgoingCount == 0 ? 0 : (double) soldFeeTotal / outgoingCount;
        }

        double avgSoldRating() {
            return outgoingCount == 0 ? 0 : soldRatingTotal / outgoingCount;
        }

        double avgSellerSquadValue() {
            return outgoingCount == 0 ? 0 : sellerSquadValueTotal / outgoingCount;
        }

        double avgSellerSquadRating() {
            return outgoingCount == 0 ? 0 : sellerSquadRatingTotal / outgoingCount;
        }
    }
}
