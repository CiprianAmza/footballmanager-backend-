package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A/B benchmark against PostgreSQL, with both variants starting from the same
 * rolled-back database state. Timing includes the final persistence phase.
 * Requires an isolated PostgreSQL database named ai_market_benchmark. Example:
 * AI_MARKET_BENCHMARK_DB_URL=jdbc:postgresql://localhost:5433/ai_market_benchmark
 * mvn -Dit.test=AiTransferMarketBenchmarkIT verify
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration/postgresql",
        "spring.jpa.hibernate.ddl-auto=update",
        "match.engine.match-plan.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "AI_MARKET_BENCHMARK_DB_URL", matches = "jdbc:postgresql:.*ai_market_benchmark.*")
class AiTransferMarketBenchmarkIT {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AI_MARKET_BENCHMARK_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AI_MARKET_BENCHMARK_DB_USER", "benchmark"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AI_MARKET_BENCHMARK_DB_PASSWORD", "benchmark"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private EndOfSeasonProcessor processor;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private FinancialRecordRepository financialRecords;
    @Autowired private TransferRepository transferRepository;
    @Autowired private RoundRepository rounds;
    @Autowired private UserContext users;
    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private MatchSimulationOrchestrator matchSimulation;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private PlannedTransferService plannedTransferService;

    record Result(long milliseconds, Map<Long, Long> buyerByPlayer,
                  Map<Long, Long> ownersByPlayer, Map<Long, Long> budgetsByTeam,
                  List<String> transferLedger, long totalFees, long savedTransfers) {}

    @Test
    void compareLegacyAndMemoryWindowFromIdenticalPostgresState() throws Exception {
        // Both runs use the same seeded strategy and rollback all market changes.
        Result legacy = run("LEGACY");
        Result memory = run("MEMORY");

        assertThat(memory.buyerByPlayer()).isNotEmpty();
        assertThat(memory.buyerByPlayer()).isEqualTo(legacy.buyerByPlayer());
        assertThat(memory.ownersByPlayer()).isEqualTo(memory.buyerByPlayer());
        assertThat(memory.ownersByPlayer()).isEqualTo(legacy.ownersByPlayer());
        assertThat(memory.budgetsByTeam()).isEqualTo(legacy.budgetsByTeam());
        assertThat(memory.transferLedger()).isEqualTo(legacy.transferLedger());
        assertThat(memory.totalFees()).isEqualTo(legacy.totalFees());
        assertThat(memory.savedTransfers()).isEqualTo(memory.buyerByPlayer().size());
        assertThat(memory.savedTransfers()).isEqualTo(legacy.savedTransfers());

        double speedup = (double) legacy.milliseconds() / Math.max(1, memory.milliseconds());
        String report = "legacy_ms=" + legacy.milliseconds() + "\n"
                + "memory_ms=" + memory.milliseconds() + "\n"
                + "speedup=" + String.format(java.util.Locale.ROOT, "%.2f", speedup) + "x\n"
                + "transfers=" + memory.buyerByPlayer().size() + "\n"
                + "database=isolated PostgreSQL\n"
                + "scope=AI market including final persistence; excludes season preparation\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "ai-transfer-market-benchmark.txt"), report);
        System.out.println("=== AI TRANSFER MARKET A/B ===\n" + report);
    }

    private Result run(String mode) {
        ReflectionTestUtils.setField(processor, "marketPersistenceMode", mode);
        strategy.setRandomForTesting(new Random(20261027L));
        matchSimulation.invalidateAllRatingCaches();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            List<Team> allTeams = teams.findAll();
            // A fresh bootstrap has little transfer cash. Give both variants the
            // same nonzero budget so settlement is actually exercised.
            allTeams.forEach(team -> team.setTransferBudget(100_000_000L));
            teams.saveAll(allTeams);
            entityManager.flush();
            Map<Long, Team> byId = allTeams.stream()
                    .collect(Collectors.toMap(Team::getId, team -> team));
            Set<Long> humanTeamIds = Set.copyOf(users.getAllHumanTeamIds());
            int season = (int) rounds.findById(1L).orElseThrow().getSeason();

            long started = System.nanoTime();
            List<Transfer> transfers = processor.runAiTransferMarket(
                    allTeams.stream().map(Team::getId).toList(), humanTeamIds,
                    byId, season, season);
            plannedTransferService.drainWindow(); // MEMORY plans the moves over the window; LEGACY made them already
            entityManager.flush();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            Map<Long, Long> destinations = transfers.stream().collect(Collectors.toMap(
                    Transfer::getPlayerId, Transfer::getBuyTeamId));
            Map<Long, Long> owners = humans.findAllById(destinations.keySet()).stream()
                    .collect(Collectors.toMap(player -> player.getId(), player -> player.getTeamId()));
            Map<Long, Long> budgets = teams.findAll().stream().collect(Collectors.toMap(
                    Team::getId, Team::getTransferBudget));
            List<String> ledger = financialRecords.findAllBySeasonNumber(season).stream()
                    .filter(record -> record.getCategory().startsWith("TRANSFER_"))
                    .map(record -> record.getTeamId() + ":" + record.getCategory() + ":"
                            + record.getAmount() + ":" + record.getDescription())
                    .sorted().toList();
            long fees = transfers.stream().mapToLong(Transfer::getPlayerTransferValue).sum();
            long saved = transferRepository.findAllBySeasonNumber(season).size();
            status.setRollbackOnly();
            return new Result(elapsedMs, destinations, owners, budgets, ledger, fees, saved);
        });
    }
}
