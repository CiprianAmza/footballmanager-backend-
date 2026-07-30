package com.footballmanagergamesimulator.testutil;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.service.TransferValueCalculator;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.MatchingPass;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.SquadDepthChart;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import com.footballmanagergamesimulator.util.TypeNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransferMarketDiagnostics {

    public static final Map<Long, String> STRATEGY_NAME = Map.of(
            TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, "Academy",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, "BuyYoungSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, "BuyFreeSellHigh",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, "BuyMidSellMid",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, "BuyTopSellWorst",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_TOP, "BuyTopSellTop");

    public enum NoTransferCause {
        NO_BUY_TARGETS("NO_BUY_TARGETS"),
        NO_MARKET_MATCH_NO_ELIGIBLE_SELLER("NO_MARKET_MATCH:NO_ELIGIBLE_SELLER"),
        NO_MARKET_MATCH_INSUFFICIENT_BUDGET("NO_MARKET_MATCH:INSUFFICIENT_BUDGET");

        private final String code;

        NoTransferCause(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record TeamIntent(
            long teamId,
            String teamName,
            long strategyId,
            List<PlayerTransferView> sellCandidates,
            BuyPlanTransferView buyPlan,
            long transferBudget,
            SquadDepthChart depthChart,
            Map<String, Integer> squadCountsByBasePosition,
            double squadAverageRating,
            double squadAverageValue,
            long squadTotalValue) {
    }

    public record TeamNoTransferDiagnostic(
            TeamIntent intent,
            NoTransferCause cause,
            boolean hasIncomingMatch,
            boolean hasOutgoingMatch,
            boolean budgetBlocked) {
    }

    private TransferMarketDiagnostics() {
    }

    /**
     * Build each club's depth chart from the match engine's own best XI — the same
     * object {@code EndOfSeasonProcessor} feeds the strategies.
     */
    public static SquadDepthChart depthChartFor(Team team,
                                                HumanRepository humanRepository,
                                                MatchSimulationOrchestrator orchestrator,
                                                MatchEngineConfig matchEngineConfig) {
        List<Human> squad = humanRepository
                .findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .toList();
        return SquadDepthChart.build(
                team.getId(),
                squad,
                orchestrator.startersFor(team.getId()),
                (natural, used) -> matchEngineConfig.getPlayerValue().familiarity(natural, used));
    }

    /**
     * Snapshot what every club intends to do this window.
     *
     * <p>Draw order mirrors production exactly: <b>all</b> sell lists first, then
     * <b>all</b> buy plans, over the teams in the order given. The strategies share
     * one seeded {@link java.util.Random}, so interleaving sell/buy per club — as an
     * earlier version of this helper did — consumes the RNG in a different order and
     * yields intents the pipeline never actually computed.
     */
    public static Map<Long, TeamIntent> snapshotTeamIntents(List<Team> teams,
                                                            CompositeTransferStrategy strategy,
                                                            HumanRepository humanRepository,
                                                            TacticService tacticService,
                                                            MatchSimulationOrchestrator orchestrator,
                                                            MatchEngineConfig matchEngineConfig) {
        Map<String, Integer> minPos = tacticService.getMinimumPositionNeeded();
        Map<String, Integer> maxPos = tacticService.getMaximumPositionAllowed();

        Map<Long, SquadDepthChart> charts = new LinkedHashMap<>();
        Map<Long, List<Human>> squads = new LinkedHashMap<>();
        for (Team team : teams) {
            charts.put(team.getId(), depthChartFor(team, humanRepository, orchestrator, matchEngineConfig));
            squads.put(team.getId(), humanRepository
                    .findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE).stream()
                    .filter(player -> !player.isRetired())
                    .toList());
        }

        // Pass 1 — every sell list, in team order.
        Map<Long, List<PlayerTransferView>> sellByTeam = new LinkedHashMap<>();
        for (Team team : teams) {
            sellByTeam.put(team.getId(),
                    List.copyOf(strategy.playersToSell(team, charts.get(team.getId()), minPos)));
        }
        // Pass 2 — every buy plan, in the same order.
        Map<Long, BuyPlanTransferView> buyByTeam = new LinkedHashMap<>();
        for (Team team : teams) {
            buyByTeam.put(team.getId(), strategy.playersToBuy(team, charts.get(team.getId()), maxPos));
        }

        Map<Long, TeamIntent> intents = new LinkedHashMap<>();
        for (Team team : teams) {
            List<Human> squad = squads.get(team.getId());
            double squadAverageRating = squad.stream().mapToDouble(Human::getRating).average().orElse(0);
            double squadAverageValue = squad.stream()
                    .mapToLong(TransferMarketDiagnostics::transferValue)
                    .average()
                    .orElse(0);
            long squadTotalValue = squad.stream()
                    .mapToLong(TransferMarketDiagnostics::transferValue)
                    .sum();

            intents.put(team.getId(), new TeamIntent(
                    team.getId(),
                    team.getName(),
                    team.getStrategy() == null ? 0L : team.getStrategy(),
                    sellByTeam.get(team.getId()),
                    buyByTeam.get(team.getId()),
                    team.getTransferBudget(),
                    charts.get(team.getId()),
                    charts.get(team.getId()).squadCountsByBasePosition(),
                    squadAverageRating,
                    squadAverageValue,
                    squadTotalValue));
        }
        return intents;
    }

    public static List<TeamNoTransferDiagnostic> classifyNoTransferTeams(Map<Long, TeamIntent> intents,
                                                                         TransferMarketService transferMarketService) {
        Map<String, List<PlayerTransferView>> marketByPosition = new HashMap<>();
        for (TeamIntent intent : intents.values()) {
            for (PlayerTransferView candidate : intent.sellCandidates()) {
                marketByPosition.computeIfAbsent(candidate.getPosition(), ignored -> new ArrayList<>()).add(candidate);
            }
        }

        List<TeamNoTransferDiagnostic> diagnostics = new ArrayList<>();
        List<TeamIntent> allTeams = new ArrayList<>(intents.values());
        for (TeamIntent intent : allTeams) {
            BuyPlanTransferView buyPlan = intent.buyPlan();

            boolean hasIncomingMatch = false;
            boolean buySideBudgetBlocked = false;
            if (buyPlan != null && buyPlan.getPositions() != null && !buyPlan.getPositions().isEmpty()) {
                long spendable = Math.min(intent.transferBudget(), buyPlan.getSpendingCap());
                for (var desired : buyPlan.getPositions()) {
                    for (PlayerTransferView candidate : marketByPosition.getOrDefault(desired.getPosition(), List.of())) {
                        if (candidate.getTeamId() == intent.teamId()) continue;
                        if (!transferMarketService.canBeTransfered(candidate, buyPlan, desired, MatchingPass.PRIMARY)) continue;
                        long fee = transferValue(candidate);
                        if (fee <= spendable) {
                            hasIncomingMatch = true;
                            break;
                        }
                        buySideBudgetBlocked = true;
                    }
                    if (hasIncomingMatch) break;
                }
            }

            boolean hasOutgoingMatch = false;
            boolean sellSideBudgetBlocked = false;
            for (PlayerTransferView sellCandidate : intent.sellCandidates()) {
                long fee = transferValue(sellCandidate);
                for (TeamIntent buyerIntent : allTeams) {
                    if (buyerIntent.teamId() == intent.teamId()) continue;
                    BuyPlanTransferView buyerPlan = buyerIntent.buyPlan();
                    if (buyerPlan == null || buyerPlan.getPositions() == null || buyerPlan.getPositions().isEmpty()) {
                        continue;
                    }
                    long buyerSpendable = Math.min(buyerIntent.transferBudget(), buyerPlan.getSpendingCap());
                    for (var desired : buyerPlan.getPositions()) {
                        if (!transferMarketService.canBeTransfered(sellCandidate, buyerPlan, desired, MatchingPass.PRIMARY)) continue;
                        if (fee <= buyerSpendable) {
                            hasOutgoingMatch = true;
                            break;
                        }
                        sellSideBudgetBlocked = true;
                    }
                    if (hasOutgoingMatch) break;
                }
                if (hasOutgoingMatch) break;
            }

            if (hasIncomingMatch || hasOutgoingMatch) continue;

            NoTransferCause cause;
            boolean hasBuyTargets = buyPlan != null && buyPlan.getPositions() != null && !buyPlan.getPositions().isEmpty();
            boolean budgetBlocked = buySideBudgetBlocked || sellSideBudgetBlocked;
            if (!hasBuyTargets) {
                cause = NoTransferCause.NO_BUY_TARGETS;
            } else if (budgetBlocked) {
                cause = NoTransferCause.NO_MARKET_MATCH_INSUFFICIENT_BUDGET;
            } else {
                cause = NoTransferCause.NO_MARKET_MATCH_NO_ELIGIBLE_SELLER;
            }

            diagnostics.add(new TeamNoTransferDiagnostic(intent, cause, hasIncomingMatch, hasOutgoingMatch, budgetBlocked));
        }

        diagnostics.sort(Comparator.comparingLong(d -> d.intent().teamId()));
        return diagnostics;
    }

    public static String strategyName(long strategyId) {
        return STRATEGY_NAME.getOrDefault(strategyId, "Unmapped");
    }

    public static long transferValue(Human player) {
        return TransferValueCalculator.calculate(player.getAge(), player.getPosition(), player.getRating());
    }

    public static long transferValue(PlayerTransferView player) {
        if (player.isFreeAgent()) return 0L;
        return TransferValueCalculator.calculate(player.getAge(), player.getPosition(), player.getRating());
    }
}
