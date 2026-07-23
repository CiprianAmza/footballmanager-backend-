package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.OwnershipRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase3-clubs;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true",
        "regent.economy.chairman-starting-wealth-max=900000000000000000",
        "regent.club.minimum-protected-reserve=1000",
        "regent.club.protected-wage-months=0"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase3ClubEconomyIT {
    private static final long RICH = 100_000_000_000L;

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private PersonProfileService profileService;
    @Autowired private PersonalAccountRepository accountRepository;
    @Autowired private PersonalLedgerEntryRepository ledgerRepository;
    @Autowired private PersonalAccountingService accountingService;
    @Autowired private TeamRepository teamRepository;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private PortfolioPositionRepository positionRepository;
    @Autowired private ClubCapTableStateRepository stateRepository;
    @Autowired private ClubShareholdingRepository legacyShareRepository;
    @Autowired private OwnershipRepository legacyOwnershipRepository;
    @Autowired private ClubCapTableService capTableService;
    @Autowired private MarketTradingService tradingService;
    @Autowired private TakeoverService takeoverService;
    @Autowired private TakeoverQuoteRepository quoteRepository;
    @Autowired private TakeoverExecutionRepository executionRepository;
    @Autowired private ClubTreasuryService treasuryService;
    @Autowired private ClubCashTransferRepository transferRepository;
    @Autowired private ClubFinancialObligationRepository obligationRepository;
    @Autowired private FinancialRecordRepository financialRecordRepository;
    @Autowired private WealthQueryService wealthQueryService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private Phase3TransactionProbe probe;

    @Test
    void legacyMigrationIsDeterministicIdempotentAndRejectsGlobalOverAllocation() {
        Team team = freshTeam(10);
        MarketInstrument instrument = instrumentRepository.findByTeamId(team.getId()).orElseThrow();
        List<PersonalAccount> humans = accountRepository.findAll().stream()
                .filter(value -> value.getOwnerHumanId() != null).limit(2).toList();
        assertThat(humans).hasSize(2);
        removeCapState(team, instrument);
        legacyShareRepository.save(legacy(humans.get(0).getOwnerHumanId(), team.getId(), 60));
        ClubShareholding excess = legacyShareRepository.save(
                legacy(humans.get(1).getOwnerHumanId(), team.getId(), 41));

        assertThatThrownBy(() -> capTableService.ensureMigrated(team.getId()))
                .isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("CAP_TABLE_OVER_ALLOCATED"));
        legacyShareRepository.delete(excess);

        ClubCapTableService.CapTable migrated = capTableService.ensureMigrated(team.getId());
        assertThat(migrated.holdings()).hasSize(1);
        assertThat(migrated.holdings().get(0).quantity()).isEqualTo(600_000);
        assertThat(migrated.freeFloat()).isEqualTo(400_000);
        assertThat(migrated.controllingAccountId()).isEqualTo(humans.get(0).getId());
        assertSupply(instrument.getId());
        long positions = positionRepository.count();

        ClubCapTableService.CapTable replay = capTableService.ensureMigrated(team.getId());
        assertThat(positionRepository.count()).isEqualTo(positions);
        assertThat(replay).usingRecursiveComparison().ignoringFields("version").isEqualTo(migrated);
        assertThat(legacyOwnershipRepository.findAllByTeamId(team.getId())).hasSize(1);
    }

    @Test
    void takeoverHappyPathRetryStaleCashMinorityAndConcurrentLastControl() throws Exception {
        Team happyTeam = freshTeam(20);
        PersonProfile happy = chairman("takeover-happy", RICH);
        TakeoverService.QuoteResult quote = takeoverService.quote(happy, happyTeam.getId(), "happy-quote");
        assertThat(quote.quote().getPremiumBps()).isEqualTo(2_000);
        TakeoverService.ExecutionResult execution = takeoverService.execute(
                happy, happyTeam.getId(), quote.quote().getQuoteKey(), "happy-execute");
        TakeoverService.ExecutionResult replay = takeoverService.execute(
                happy, happyTeam.getId(), quote.quote().getQuoteKey(), "happy-execute");
        assertThat(replay.replayed()).isTrue();
        assertThat(executionRepository.count()).isGreaterThanOrEqualTo(1);
        PersonalAccount happyAccount = accountRepository.findByProfileId(happy.getId()).orElseThrow();
        MarketInstrument happyInstrument = instrumentRepository.findByTeamId(happyTeam.getId()).orElseThrow();
        assertThat(positionRepository.findByAccountIdAndInstrumentId(happyAccount.getId(), happyInstrument.getId())
                .orElseThrow().getQuantity()).isEqualTo(happyInstrument.getTotalSupply());
        assertThat(stateRepository.findByTeamId(happyTeam.getId()).orElseThrow().getControllingAccountId())
                .isEqualTo(happyAccount.getId());
        assertSupply(happyInstrument.getId());
        accountingService.assertReconciled(happyAccount.getId());

        Team staleTeam = freshTeam(21);
        PersonProfile stale = chairman("takeover-stale", RICH);
        TakeoverQuote staleQuote = takeoverService.quote(stale, staleTeam.getId(), "stale-quote").quote();
        staleTeam.setReputation(staleTeam.getReputation() + 1);
        teamRepository.saveAndFlush(staleTeam);
        assertCode(() -> takeoverService.execute(stale, staleTeam.getId(), staleQuote.getQuoteKey(), "stale-execute"),
                "TAKEOVER_QUOTE_STALE");

        Team poorTeam = freshTeam(22);
        PersonProfile poor = chairman("takeover-poor", 1_000_000L);
        TakeoverQuote poorQuote = takeoverService.quote(poor, poorTeam.getId(), "poor-quote").quote();
        assertCode(() -> takeoverService.execute(poor, poorTeam.getId(), poorQuote.getQuoteKey(), "poor-execute"),
                "INSUFFICIENT_FUNDS");

        Team protectedTeam = freshTeam(23);
        PersonProfile minority = chairman("takeover-minority", RICH);
        MarketInstrument protectedInstrument = instrumentRepository.findByTeamId(protectedTeam.getId()).orElseThrow();
        tradingService.trade(minority, protectedInstrument.getId(), MarketTradeSide.BUY, 1, "minority-share");
        PersonProfile bidder = chairman("takeover-bidder", RICH);
        assertCode(() -> takeoverService.quote(bidder, protectedTeam.getId(), "protected-quote"),
                "PROTECTED_MINORITY");
        assertSupply(protectedInstrument.getId());

        Team raceTeam = freshTeam(24);
        PersonProfile first = chairman("takeover-race-a", RICH);
        PersonProfile second = chairman("takeover-race-b", RICH);
        TakeoverQuote firstQuote = takeoverService.quote(first, raceTeam.getId(), "race-quote-a").quote();
        TakeoverQuote secondQuote = takeoverService.quote(second, raceTeam.getId(), "race-quote-b").quote();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> left = pool.submit(() -> executeAfter(start, first, raceTeam, firstQuote, "race-a"));
            Future<String> right = pool.submit(() -> executeAfter(start, second, raceTeam, secondQuote, "race-b"));
            start.countDown();
            List<String> outcomes = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).contains("OK");
            assertThat(outcomes.stream().filter("OK"::equals).count()).isOne();
        }
        ClubCapTableService.CapTable raced = capTableService.ensureMigrated(raceTeam.getId());
        assertThat(raced.holdings()).singleElement().satisfies(value -> {
            assertThat(value.quantity()).isEqualTo(raced.issuedShares());
            assertThat(value.controlling()).isTrue();
        });
        assertSupply(raced.instrumentId());
    }

    @Test
    void takeoverRollsBackAfterEveryPartialStage() {
        Team team = freshTeam(30);
        PersonProfile buyer = chairman("takeover-rollback", RICH);
        TakeoverQuote quote = takeoverService.quote(buyer, team.getId(), "rollback-quote").quote();
        PersonalAccount account = accountRepository.findByProfileId(buyer.getId()).orElseThrow();
        MarketInstrument instrument = instrumentRepository.findByTeamId(team.getId()).orElseThrow();
        long cash = account.getCashBalance();
        long clubCash = team.getTotalFinances();
        long ledgerCount = ledgerRepository.count();
        long executionCount = executionRepository.count();
        for (String stage : List.of("TAKEOVER_AFTER_BUYER_DEBIT", "TAKEOVER_AFTER_SELLER_CREDITS",
                "TAKEOVER_AFTER_SHARE_TRANSFER", "TAKEOVER_AFTER_CONTROL",
                "TAKEOVER_AFTER_CLUB_LEDGER", "TAKEOVER_AFTER_EXECUTION_RECORD")) {
            reset(probe);
            doThrow(new IllegalStateException(stage)).when(probe).checkpoint(stage);
            assertThatThrownBy(() -> takeoverService.execute(
                    buyer, team.getId(), quote.getQuoteKey(), "rollback-" + stage)).hasMessage(stage);
            assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(cash);
            assertThat(teamRepository.findById(team.getId()).orElseThrow().getTotalFinances()).isEqualTo(clubCash);
            assertThat(instrumentRepository.findById(instrument.getId()).orElseThrow().getAvailableSupply())
                    .isEqualTo(instrument.getTotalSupply());
            assertThat(positionRepository.sumQuantityByInstrumentId(instrument.getId())).isZero();
            assertThat(ledgerRepository.count()).isEqualTo(ledgerCount);
            assertThat(executionRepository.count()).isEqualTo(executionCount);
            assertThat(quoteRepository.findById(quote.getId()).orElseThrow().getStatus())
                    .isEqualTo(TakeoverQuoteStatus.OPEN);
        }
        reset(probe);
    }

    @Test
    void treasuryTransfersEnforceCashReserveDebtRetryMirrorsRollbackConcurrencyAndWealth() throws Exception {
        Team team = freshTeam(40);
        PersonProfile owner = chairman("treasury-owner", RICH);
        TakeoverQuote quote = takeoverService.quote(owner, team.getId(), "treasury-acquire-q").quote();
        takeoverService.execute(owner, team.getId(), quote.getQuoteKey(), "treasury-acquire-x");
        PersonalAccount account = accountRepository.findByProfileId(owner.getId()).orElseThrow();

        EconomyDtos.WealthView beforeInjection = wealthQueryService.wealth(owner.getId());
        ClubTreasuryService.TransferResult injection = treasuryService.transfer(
                owner, team.getId(), ClubCashTransferDirection.INJECTION, 10_000, "inject-once");
        ClubTreasuryService.TransferResult replay = treasuryService.transfer(
                owner, team.getId(), ClubCashTransferDirection.INJECTION, 10_000, "inject-once");
        assertThat(replay.replayed()).isTrue();
        assertThat(transferRepository.findByAccountIdAndIdempotencyKey(account.getId(), "inject-once")).isPresent();
        assertMirrored(injection.transfer());
        EconomyDtos.WealthView afterInjection = wealthQueryService.wealth(owner.getId());
        assertThat(afterInjection.cash().amount()).isEqualTo(beforeInjection.cash().amount() - 10_000);
        assertThat(afterInjection.investmentValue().amount()).isZero();
        assertThat(afterInjection.clubEquityValue().amount())
                .isEqualTo(beforeInjection.clubEquityValue().amount() + 10_000);
        assertThat(afterInjection.netWorth().amount()).isEqualTo(beforeInjection.netWorth().amount());

        ClubTreasuryService.TransferResult withdrawal = treasuryService.transfer(
                owner, team.getId(), ClubCashTransferDirection.WITHDRAWAL, 5_000, "withdraw-once");
        assertMirrored(withdrawal.transfer());
        accountingService.assertReconciled(account.getId());
        long cashNow = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
        assertCode(() -> treasuryService.transfer(owner, team.getId(), ClubCashTransferDirection.INJECTION,
                cashNow + 1, "inject-too-much"), "INSUFFICIENT_FUNDS");

        Team locked = teamRepository.findById(team.getId()).orElseThrow();
        locked.setTotalFinances(500);
        teamRepository.saveAndFlush(locked);
        assertCode(() -> treasuryService.transfer(owner, team.getId(), ClubCashTransferDirection.WITHDRAWAL,
                1, "reserve-too-low"), "INSUFFICIENT_DISTRIBUTABLE_CASH");
        locked.setTotalFinances(10_000);
        locked.setDebt(1);
        teamRepository.saveAndFlush(locked);
        assertCode(() -> treasuryService.transfer(owner, team.getId(), ClubCashTransferDirection.WITHDRAWAL,
                1, "debt-block"), "WITHDRAWAL_RESTRICTED");
        locked.setDebt(0);
        teamRepository.saveAndFlush(locked);

        for (String stage : List.of("TREASURY_AFTER_PERSONAL_LEDGER", "TREASURY_AFTER_CLUB_BALANCE",
                "TREASURY_AFTER_CLUB_LEDGER", "TREASURY_AFTER_TRANSFER_RECORD")) {
            long personalBefore = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
            long clubBefore = teamRepository.findById(team.getId()).orElseThrow().getTotalFinances();
            long transferCount = transferRepository.count();
            reset(probe);
            doThrow(new IllegalStateException(stage)).when(probe).checkpoint(stage);
            assertThatThrownBy(() -> treasuryService.transfer(owner, team.getId(),
                    ClubCashTransferDirection.INJECTION, 10, "rollback-" + stage)).hasMessage(stage);
            assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
                    .isEqualTo(personalBefore);
            assertThat(teamRepository.findById(team.getId()).orElseThrow().getTotalFinances()).isEqualTo(clubBefore);
            assertThat(transferRepository.count()).isEqualTo(transferCount);
        }
        reset(probe);

        locked = teamRepository.findById(team.getId()).orElseThrow();
        locked.setTotalFinances(1_500);
        teamRepository.saveAndFlush(locked);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> first = pool.submit(() -> withdrawAfter(start, owner, team, "concurrent-a"));
            Future<String> second = pool.submit(() -> withdrawAfter(start, owner, team, "concurrent-b"));
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INSUFFICIENT_DISTRIBUTABLE_CASH");
        }
        accountingService.assertReconciled(account.getId());
    }

    @Test
    void httpSecurityMatrixUsesPrincipalOnlyRolesFlagAuthAndCsrf() throws Exception {
        Team team = freshTeam(50);
        register("security-manager", CareerRole.MANAGER, null);
        register("security-chairman", CareerRole.CHAIRMAN, RICH);
        User admin = register("security-admin", CareerRole.MANAGER, null);
        admin.setRoles("USER,ADMIN");
        userRepository.saveAndFlush(admin);
        MockHttpSession manager = login("security-manager");
        MockHttpSession chairman = login("security-chairman");
        MockHttpSession adminSession = login("security-admin");

        String body = "{\"idempotencyKey\":\"security-q\",\"accountId\":999999,"
                + "\"ownerId\":999999,\"price\":1,\"control\":true}";
        mockMvc.perform(post("/api/clubs/{teamId}/takeover-quotes", team.getId()).session(manager).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAIRMAN_REQUIRED"));
        mockMvc.perform(post("/api/clubs/{teamId}/takeover-quotes", team.getId()).session(adminSession).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAIRMAN_REQUIRED"));
        mockMvc.perform(post("/api/clubs/{teamId}/takeover-quotes", team.getId()).session(chairman)
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/clubs/{teamId}/takeover-quotes", team.getId()).session(chairman).with(csrf())
                        .header("X-User-Id", "999999").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(team.getId()))
                .andExpect(jsonPath("$.unitPrice.amount").value(org.hamcrest.Matchers.greaterThan(1)));
        mockMvc.perform(post("/api/clubs/{teamId}/treasury-transfers", team.getId()).session(chairman).with(csrf())
                        .contentType("application/json")
                        .content("{\"direction\":\"INJECTION\",\"amount\":1,\"idempotencyKey\":\"not-owner\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_CONTROL_REQUIRED"));
        mockMvc.perform(get("/api/clubs/{teamId}/chairman-dashboard", team.getId()).session(chairman))
                .andExpect(status().isOk()).andExpect(jsonPath("$.teamId").value(team.getId()));
    }

    private Team freshTeam(int index) {
        Team team = teamRepository.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId))
                .skip(index).findFirst().orElseThrow();
        MarketInstrument instrument = instrumentRepository.findByTeamId(team.getId()).orElseThrow();
        removeCapState(team, instrument);
        return teamRepository.findById(team.getId()).orElseThrow();
    }

    private void removeCapState(Team team, MarketInstrument instrument) {
        stateRepository.findByInstrumentId(instrument.getId()).ifPresent(stateRepository::delete);
        stateRepository.flush();
        positionRepository.deleteAll(positionRepository
                .findAllByInstrumentIdAndQuantityGreaterThanOrderByAccountIdAsc(instrument.getId(), -1));
        positionRepository.flush();
        legacyShareRepository.deleteAll(legacyShareRepository.findAllByTeamId(team.getId()));
        legacyOwnershipRepository.deleteAll(legacyOwnershipRepository.findAllByTeamId(team.getId()));
        instrument.setAvailableSupply(instrument.getTotalSupply());
        instrumentRepository.saveAndFlush(instrument);
        obligationRepository.deleteAll(obligationRepository
                .findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(team.getId()));
        capTableService.ensureMigrated(team.getId());
    }

    private ClubShareholding legacy(long humanId, long teamId, double percent) {
        ClubShareholding value = new ClubShareholding();
        value.setHumanId(humanId);
        value.setTeamId(teamId);
        value.setPercent(percent);
        return value;
    }

    private PersonProfile chairman(String username, long wealth) {
        return profileService.requireForUser(register(username, CareerRole.CHAIRMAN, wealth));
    }

    private User register(String username, CareerRole role, Long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, role, wealth));
    }

    private MockHttpSession login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username, "password", "correct-password"));
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private String executeAfter(CountDownLatch start, PersonProfile profile, Team team,
                                TakeoverQuote quote, String key) throws InterruptedException {
        start.await();
        try {
            takeoverService.execute(profile, team.getId(), quote.getQuoteKey(), key);
            return "OK";
        } catch (EconomyConflictException exception) {
            return exception.getCode();
        }
    }

    private String withdrawAfter(CountDownLatch start, PersonProfile profile,
                                 Team team, String key) throws InterruptedException {
        start.await();
        try {
            treasuryService.transfer(profile, team.getId(), ClubCashTransferDirection.WITHDRAWAL, 400, key);
            return "OK";
        } catch (EconomyConflictException exception) {
            return exception.getCode();
        }
    }

    private void assertMirrored(ClubCashTransfer transfer) {
        assertThat(ledgerRepository.findAllByCorrelationId(transfer.getCorrelationId())).hasSize(1);
        assertThat(financialRecordRepository.findAll().stream()
                .filter(value -> value.getTeamId() == transfer.getTeamId())
                .filter(value -> value.getDescription().contains(transfer.getCorrelationId())).toList()).hasSize(1);
    }

    private void assertSupply(long instrumentId) {
        MarketInstrument instrument = instrumentRepository.findById(instrumentId).orElseThrow();
        assertThat(instrument.getAvailableSupply() + positionRepository.sumQuantityByInstrumentId(instrumentId))
                .isEqualTo(instrument.getTotalSupply());
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode()).isEqualTo(code));
    }
}
