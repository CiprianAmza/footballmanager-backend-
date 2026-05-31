package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Human;
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
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.service.TransferValueCalculator;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.TransferMarketDiagnostics;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
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
import java.util.Comparator;
import java.util.HashMap;
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
 *   <li><b>#2</b> total squad-value drift per strategy, before vs after the campaign
 *       (plus the legacy top-11 view for comparison with the earlier handoff);</li>
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
@TestPropertySource(properties = {
        "bootstrap.seed=20260528",
        "transfer.economy.fuzz=true"
})
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
    private static final int SEASONS = 1;          // one full transfer campaign; keeps fuzz practical locally
    private static final long LEAGUE_TYPE_ID = 1L;

    @AfterEach
    void restoreProductionRng() {
        matchSimulationService.setRandomForTesting(new Random());
        strategy.setRandomForTesting(new Random());
    }

    // ============================================================
    //  Req #2 + #3 — campaign on the real pipeline
    // ============================================================

    @Test
    @DisplayName("Squad-value drift, transfer behaviour, and no-transfer causes over a campaign")
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

        Map<Long, Long> squadValueBefore = new HashMap<>();
        Map<Long, Long> firstElevenBefore = new HashMap<>();
        for (Team t : allTeams) {
            squadValueBefore.put(t.getId(), squadValue(t.getId()));
            firstElevenBefore.put(t.getId(), firstElevenValue(t.getId()));
        }

        List<TransferMarketDiagnostics.TeamNoTransferDiagnostic> noTransferDiagnostics = List.of();
        Map<Long, StrategyEconomyStats> statsByStrategy = new TreeMap<>();

        for (int season = 0; season < SEASONS; season++) {
            int currentSeason = currentSeason();
            matchSimulationService.setRandomForTesting(new Random(BASE_SEED + season));
            strategy.setRandomForTesting(new Random(BASE_SEED + season));

            simulateLeague(leagueCompId, currentSeason);

            if (season == 0) {
                strategy.setRandomForTesting(new Random(BASE_SEED + season));
                noTransferDiagnostics = TransferMarketDiagnostics.classifyNoTransferTeams(
                        TransferMarketDiagnostics.snapshotTeamIntents(
                                teamRepository.findAll(),
                                strategy,
                                humanRepository,
                                tacticService),
                        transferMarketService);
            }

            strategy.setRandomForTesting(new Random(BASE_SEED + season));
            seasonTransitionService.processEndOfSeason(currentSeason);

            for (Transfer transfer : transferRepository.findAllBySeasonNumber(currentSeason)) {
                statsByStrategy.computeIfAbsent(strategyByTeam.get(transfer.getSellTeamId()), ignored -> new StrategyEconomyStats())
                        .addSale(transfer);
                statsByStrategy.computeIfAbsent(strategyByTeam.get(transfer.getBuyTeamId()), ignored -> new StrategyEconomyStats())
                        .addBuy(transfer);
            }

            if (season < SEASONS - 1) {
                seasonTransitionService.processNewSeasonSetup(currentSeason);
            }
        }

        for (Team t : teamRepository.findAll().stream().sorted(Comparator.comparingLong(Team::getId)).toList()) {
            long strat = strategyByTeam.get(t.getId());
            StrategyEconomyStats stats = statsByStrategy.computeIfAbsent(strat, ignored -> new StrategyEconomyStats());
            stats.teamCount++;
            stats.squadValueBeforeTotal += squadValueBefore.get(t.getId());
            stats.squadValueAfterTotal += squadValue(t.getId());
            stats.firstElevenBeforeTotal += firstElevenBefore.get(t.getId());
            stats.firstElevenAfterTotal += firstElevenValue(t.getId());
        }

        String md = buildReport(leagueCompId, statsByStrategy, noTransferDiagnostics);
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
        assertThat(statsByStrategy.keySet())
                .as("every strategy must be represented among the teams")
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(statsByStrategy.getOrDefault(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, new StrategyEconomyStats()).incomingCount)
                .as("Academy must not complete incoming transfers")
                .isZero();
        StrategyEconomyStats buyYoungStats =
                statsByStrategy.getOrDefault(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, new StrategyEconomyStats());
        if (buyYoungStats.incomingCount > 0) {
            assertThat(buyYoungStats.maxBoughtAge)
                    .as("BuyYoungSellHigh must only buy players aged 24 or below")
                    .isLessThanOrEqualTo(24);
        }
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
            for (Human h : squad) {
                squadCount.merge(TacticService.getBasePosition(h.getPosition()), 1, Integer::sum);
            }
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

    private long squadValue(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .mapToLong(player -> TransferValueCalculator.calculate(
                        player.getAge(), player.getPosition(), player.getRating()))
                .sum();
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

    private String buildReport(long leagueCompId,
                               Map<Long, StrategyEconomyStats> statsByStrategy,
                               List<TransferMarketDiagnostics.TeamNoTransferDiagnostic> diagnostics) {
        MarkdownTable squadTable = new MarkdownTable(
                List.of("Strategy", "Teams", "Avg squad before", "Avg squad after", "Avg delta", "Delta %"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        MarkdownTable firstElevenTable = new MarkdownTable(
                List.of("Strategy", "Avg XI before", "Avg XI after", "Avg delta", "Delta %"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        MarkdownTable transferTable = new MarkdownTable(
                List.of("Strategy", "Buys", "Avg buy age", "Max buy age", "Sales", "Avg sale fee"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));

        for (Map.Entry<Long, StrategyEconomyStats> entry : statsByStrategy.entrySet()) {
            long strategyId = entry.getKey();
            StrategyEconomyStats stats = entry.getValue();
            squadTable.addRow(
                    TransferMarketDiagnostics.strategyName(strategyId),
                    String.valueOf(stats.teamCount),
                    String.format("%,.0f", stats.avgSquadBefore()),
                    String.format("%,.0f", stats.avgSquadAfter()),
                    String.format("%+,.0f", stats.avgSquadDelta()),
                    String.format("%+.1f%%", stats.squadDeltaPct()));
            firstElevenTable.addRow(
                    TransferMarketDiagnostics.strategyName(strategyId),
                    String.format("%,.0f", stats.avgFirstElevenBefore()),
                    String.format("%,.0f", stats.avgFirstElevenAfter()),
                    String.format("%+,.0f", stats.avgFirstElevenDelta()),
                    String.format("%+.1f%%", stats.firstElevenDeltaPct()));
            transferTable.addRow(
                    TransferMarketDiagnostics.strategyName(strategyId),
                    String.valueOf(stats.incomingCount),
                    stats.incomingCount == 0 ? "—" : String.format("%.1f", stats.avgBoughtAge()),
                    stats.incomingCount == 0 ? "—" : String.valueOf(stats.maxBoughtAge),
                    String.valueOf(stats.outgoingCount),
                    stats.outgoingCount == 0 ? "—" : String.format("%,.0f", stats.avgSoldFee()));
        }

        Map<String, Integer> causeTally = new TreeMap<>();
        Map<String, Integer> flaggedByStrategy = new TreeMap<>();
        for (TransferMarketDiagnostics.TeamNoTransferDiagnostic diagnostic : diagnostics) {
            causeTally.merge(diagnostic.cause().code(), 1, Integer::sum);
            flaggedByStrategy.merge(
                    TransferMarketDiagnostics.strategyName(diagnostic.intent().strategyId()),
                    1,
                    Integer::sum);
        }

        MarkdownTable causeTable = new MarkdownTable(
                List.of("Cause", "Teams"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        causeTally.forEach((cause, count) -> causeTable.addRow(cause, String.valueOf(count)));

        MarkdownTable flaggedTable = new MarkdownTable(
                List.of("Strategy", "Flagged teams"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        for (long strategyId = 1L; strategyId <= 5L; strategyId++) {
            flaggedTable.addRow(
                    TransferMarketDiagnostics.strategyName(strategyId),
                    String.valueOf(flaggedByStrategy.getOrDefault(
                            TransferMarketDiagnostics.strategyName(strategyId), 0)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Transfer economy report\n\n");
        sb.append("- seed: ").append(BASE_SEED).append('\n');
        sb.append("- seasons (campaign length): ").append(SEASONS).append('\n');
        sb.append("- league simulated: competition ").append(leagueCompId).append('\n');
        sb.append("- primary value metric: SUM TransferValueCalculator over the whole active squad\n");
        sb.append("- end snapshot: after the final end-of-season transfer campaign, before season ")
                .append(SEASONS + 1)
                .append(" setup\n\n");

        sb.append("## Squad value per strategy (campaign delta)\n\n");
        sb.append(squadTable.render());
        sb.append("\n## First-eleven value per strategy (legacy comparison)\n\n");
        sb.append(firstElevenTable.render());
        sb.append("\n## Completed transfer flow per strategy\n\n");
        sb.append(transferTable.render());
        sb.append("\n## Teams with no transfers - cause (season 1)\n\n");
        if (causeTally.isEmpty()) {
            sb.append("No teams were flagged by the stricter diagnostic.\n");
        } else {
            sb.append(causeTable.render());
            sb.append("\n## Flagged teams by strategy\n\n");
            sb.append(flaggedTable.render());
        }
        sb.append('\n');
        return sb.toString();
    }

    private static final class StrategyEconomyStats {
        private int teamCount;
        private long squadValueBeforeTotal;
        private long squadValueAfterTotal;
        private long firstElevenBeforeTotal;
        private long firstElevenAfterTotal;
        private int incomingCount;
        private long boughtAgeTotal;
        private int maxBoughtAge;
        private int outgoingCount;
        private long soldFeeTotal;

        void addBuy(Transfer transfer) {
            incomingCount++;
            boughtAgeTotal += transfer.getPlayerAge();
            maxBoughtAge = Math.max(maxBoughtAge, (int) transfer.getPlayerAge());
        }

        void addSale(Transfer transfer) {
            outgoingCount++;
            soldFeeTotal += transfer.getPlayerTransferValue();
        }

        double avgSquadBefore() {
            return teamCount == 0 ? 0 : (double) squadValueBeforeTotal / teamCount;
        }

        double avgSquadAfter() {
            return teamCount == 0 ? 0 : (double) squadValueAfterTotal / teamCount;
        }

        double avgSquadDelta() {
            return avgSquadAfter() - avgSquadBefore();
        }

        double squadDeltaPct() {
            double before = avgSquadBefore();
            return before == 0 ? 0 : (avgSquadDelta() / before) * 100.0;
        }

        double avgFirstElevenBefore() {
            return teamCount == 0 ? 0 : (double) firstElevenBeforeTotal / teamCount;
        }

        double avgFirstElevenAfter() {
            return teamCount == 0 ? 0 : (double) firstElevenAfterTotal / teamCount;
        }

        double avgFirstElevenDelta() {
            return avgFirstElevenAfter() - avgFirstElevenBefore();
        }

        double firstElevenDeltaPct() {
            double before = avgFirstElevenBefore();
            return before == 0 ? 0 : (avgFirstElevenDelta() / before) * 100.0;
        }

        double avgBoughtAge() {
            return incomingCount == 0 ? 0 : (double) boughtAgeTotal / incomingCount;
        }

        double avgSoldFee() {
            return outgoingCount == 0 ? 0 : (double) soldFeeTotal / outgoingCount;
        }
    }
}
