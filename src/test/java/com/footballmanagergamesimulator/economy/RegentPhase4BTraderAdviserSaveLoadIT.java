package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase4b-adviser",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.economy.chairman-starting-wealth-min=0",
        "regent.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RegentPhase4BTraderAdviserSaveLoadIT {
    @Autowired private UserService userService;
    @Autowired private PersonProfileService profileService;
    @Autowired private PersonalAccountRepository accountRepository;
    @Autowired private PersonalLedgerEntryRepository ledgerRepository;
    @Autowired private PersonalAccountingService accountingService;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private PortfolioPositionRepository positionRepository;
    @Autowired private DeterministicMarketPriceService priceService;
    @Autowired private TraderAdviserService adviserService;
    @Autowired private TraderAdviserContractRepository contractRepository;
    @Autowired private TraderAdviceRecommendationRepository adviceRepository;
    @Autowired private GameController gameController;
    @Autowired private RoundRepository roundRepository;
    @Autowired private CalendarService calendarService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void hireSalaryAdviceSecurityRetryAndSaveLoadRemainAtomicAndNonOmniscient() {
        Round round = roundRepository.findById(1L).orElseThrow();
        calendarService.getOrCreateCalendar((int) round.getSeason());
        User owner = register("adviser-owner", CareerRole.CHAIRMAN, 2_000_000L);
        User attacker = register("adviser-attacker", CareerRole.CHAIRMAN, 2_000_000L);
        PersonProfile profile = profileService.requireForUser(owner);
        PersonalAccount account = accountRepository.findByProfileId(profile.getId()).orElseThrow();

        TraderAdviserService.HireResult hire = adviserService.hire(
                profile, owner.getId(), "STRATEGIST", 30, 1, 1, "hire-strategist");
        assertThat(adviserService.hire(profile, owner.getId(), "STRATEGIST", 30, 1, 1,
                "hire-strategist").replay()).isTrue();
        assertThatThrownBy(() -> adviserService.hire(profile, owner.getId(), "STRATEGIST", 30, 1, 2,
                "hire-strategist"))
                .isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(hire.contract().getSkill()).isEqualTo(70);
        assertThat(hire.contract().getReputation()).isEqualTo(65);
        assertThat(hire.contract().getSalaryPerDay()).isEqualTo(7_500L);
        assertThatThrownBy(() -> adviserService.hire(
                profile, attacker.getId(), "VETERAN", 30, 1, 1, "forbidden"))
                .isInstanceOf(EconomyConflictException.class)
                .satisfies(error -> assertThat(((EconomyConflictException) error).getCode())
                        .isEqualTo("PROFILE_OWNERSHIP_REQUIRED"));

        priceService.processDay(1, 10);
        account = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualTo(2_000_000L - 10L * 7_500L);
        assertThat(ledgerRepository.findAllByAccountIdOrderByCreatedAtAscIdAsc(account.getId()))
                .filteredOn(value -> value.getEntryType() == LedgerEntryType.TRADER_ADVISER_SALARY)
                .hasSize(10).allSatisfy(value -> assertThat(value.getSignedAmount()).isEqualTo(-7_500L));
        priceService.processDay(1, 10);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
                .isEqualTo(2_000_000L - 10L * 7_500L);
        accountingService.assertReconciled(account.getId());

        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();
        long cashBeforeAdvice = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
        long positionsBeforeAdvice = positionRepository.count();
        TraderAdviserService.AdviceResult advice = adviserService.advise(
                profile, owner.getId(), instrument.getId(), 1, 10);
        assertThat(advice.recommendation().getExplanation()).contains("no future quote is used");
        assertThat(advice.recommendation().getConfidence()).isBetween(
                new java.math.BigDecimal("0.05"), new java.math.BigDecimal("0.99"));
        assertThat(adviserService.advise(profile, owner.getId(), instrument.getId(), 1, 10).replay()).isTrue();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(cashBeforeAdvice);
        assertThat(positionRepository.count()).isEqualTo(positionsBeforeAdvice);

        adviceRepository.deleteAll();
        adviceRepository.flush();
        priceService.processDay(1, 12);
        TraderAdviserService.AdviceResult rebuiltPastAdvice = adviserService.advise(
                profile, owner.getId(), instrument.getId(), 1, 10);
        assertThat(rebuiltPastAdvice.recommendation().getAction())
                .isEqualTo(advice.recommendation().getAction());
        assertThat(rebuiltPastAdvice.recommendation().getTrailingReturn())
                .isEqualByComparingTo(advice.recommendation().getTrailingReturn());
        assertThat(rebuiltPastAdvice.recommendation().getObservedVolatility())
                .isEqualByComparingTo(advice.recommendation().getObservedVolatility());

        Map<String, Object> save = gameController.exportGame();
        assertThat(save.get("saveVersion")).isEqualTo(11);
        assertThat((List<?>) save.get("traderAdviserContracts")).hasSize(1);
        assertThat((List<?>) save.get("traderAdviceRecommendations")).hasSize(1);
        priceService.processDay(1, 14);
        assertThat(gameController.importGame(save)).containsEntry("success", true);
        assertThat(contractRepository.findById(hire.contract().getId())).isPresent();
        assertThat(adviceRepository.findById(rebuiltPastAdvice.recommendation().getId())).isPresent();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
                .isEqualTo(2_000_000L - 12L * 7_500L);
        assertThat(adviserService.advise(profile, owner.getId(), instrument.getId(), 1, 10).replay()).isTrue();
        accountingService.assertReconciled(account.getId());
    }

    @Test
    void concurrentHireAdviceAndPayrollSerializeAndInsufficientCashTerminatesCleanly() throws Exception {
        User hireUser = register("adviser-race-hire", CareerRole.CHAIRMAN, 100_000L);
        PersonProfile hireProfile = profileService.requireForUser(hireUser);
        CountDownLatch hireStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> first = pool.submit(() -> hireAfter(
                    hireStart, hireProfile, hireUser.getId(), "race-hire-a"));
            Future<String> second = pool.submit(() -> hireAfter(
                    hireStart, hireProfile, hireUser.getId(), "race-hire-b"));
            hireStart.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "ADVISER_ALREADY_HIRED");
        }
        List<TraderAdviserContract> hireContracts = contractRepository.findAll().stream()
                .filter(value -> value.getProfileId() == hireProfile.getId()).toList();
        assertThat(hireContracts).hasSize(1).allSatisfy(value -> assertThat(value.isActive()).isTrue());

        priceService.processDay(1, 3);
        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();
        CountDownLatch adviceStart = new CountDownLatch(1);
        TraderAdviserService.AdviceResult left;
        TraderAdviserService.AdviceResult right;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<TraderAdviserService.AdviceResult> first = pool.submit(() -> adviceAfter(
                    adviceStart, hireProfile, hireUser.getId(), instrument.getId()));
            Future<TraderAdviserService.AdviceResult> second = pool.submit(() -> adviceAfter(
                    adviceStart, hireProfile, hireUser.getId(), instrument.getId()));
            adviceStart.countDown();
            left = first.get(20, TimeUnit.SECONDS);
            right = second.get(20, TimeUnit.SECONDS);
        }
        assertThat(List.of(left.replay(), right.replay())).containsExactlyInAnyOrder(false, true);
        assertThat(fingerprint(left.recommendation())).isEqualTo(fingerprint(right.recommendation()));
        assertThat(objectMapper.convertValue(left.recommendation(), Map.class))
                .isEqualTo(objectMapper.convertValue(right.recommendation(), Map.class));
        assertThat(adviceRepository.count()).isOne();

        User payrollUser = register("adviser-race-payroll", CareerRole.CHAIRMAN, 100_000L);
        PersonProfile payrollProfile = profileService.requireForUser(payrollUser);
        TraderAdviserContract payrollContract = adviserService.hire(
                payrollProfile, payrollUser.getId(), "STRATEGIST", 30, 1, 4, "payroll-hire").contract();
        PersonalAccount payrollAccount = accountRepository.findByProfileId(payrollProfile.getId()).orElseThrow();
        CountDownLatch payrollStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Void> first = pool.submit(() -> payrollAfter(payrollStart));
            Future<Void> second = pool.submit(() -> payrollAfter(payrollStart));
            payrollStart.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        List<PersonalLedgerEntry> payrollEntries = ledgerRepository
                .findAllByAccountIdOrderByCreatedAtAscIdAsc(payrollAccount.getId()).stream()
                .filter(value -> value.getEntryType() == LedgerEntryType.TRADER_ADVISER_SALARY).toList();
        assertThat(payrollEntries).hasSize(1);
        assertThat(payrollEntries.get(0).getIdempotencyKey())
                .isEqualTo("TRADER-ADVISER-SALARY:" + payrollContract.getId() + ":4");
        assertThat(accountRepository.findById(payrollAccount.getId()).orElseThrow().getCashBalance())
                .isEqualTo(92_500L);
        accountingService.assertReconciled(payrollAccount.getId());

        User poorUser = register("adviser-poor-payroll", CareerRole.CHAIRMAN, 5_000L);
        PersonProfile poorProfile = profileService.requireForUser(poorUser);
        TraderAdviserContract poorContract = adviserService.hire(
                poorProfile, poorUser.getId(), "STRATEGIST", 30, 1, 4, "poor-hire").contract();
        PersonalAccount poorAccount = accountRepository.findByProfileId(poorProfile.getId()).orElseThrow();
        adviserService.processDailyPayroll(1, 4);
        poorContract = contractRepository.findById(poorContract.getId()).orElseThrow();
        assertThat(poorContract.isActive()).isFalse();
        assertThat(poorContract.getTerminationReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(poorContract.getLastPaidAbsoluteDay()).isEqualTo(3L);
        assertThat(accountRepository.findById(poorAccount.getId()).orElseThrow().getCashBalance()).isEqualTo(5_000L);
        assertThat(ledgerRepository.findAllByAccountIdOrderByCreatedAtAscIdAsc(poorAccount.getId()))
                .filteredOn(value -> value.getEntryType() == LedgerEntryType.TRADER_ADVISER_SALARY)
                .isEmpty();
        accountingService.assertReconciled(poorAccount.getId());
    }

    private String hireAfter(CountDownLatch start, PersonProfile profile, int userId, String key)
            throws InterruptedException {
        start.await();
        try {
            adviserService.hire(profile, userId, "STRATEGIST", 30, 1, 1, key);
            return "OK";
        } catch (EconomyConflictException exception) {
            return exception.getCode();
        }
    }

    private TraderAdviserService.AdviceResult adviceAfter(CountDownLatch start, PersonProfile profile,
                                                           int userId, long instrumentId) throws InterruptedException {
        start.await();
        return adviserService.advise(profile, userId, instrumentId, 1, 3);
    }

    private Void payrollAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        adviserService.processDailyPayroll(1, 4);
        return null;
    }

    private static AdviceFingerprint fingerprint(TraderAdviceRecommendation value) {
        return new AdviceFingerprint(value.getAction(), value.getRiskClass(), value.getHorizonDays(),
                value.getConfidence(), value.getRisk(), value.getTrailingReturn(),
                value.getObservedVolatility(), value.getExplanation(), value.getModelVersion());
    }

    private User register(String username, CareerRole role, long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, role, wealth));
    }

    private record AdviceFingerprint(com.footballmanagergamesimulator.regent.market.core.AdviceAction action,
                                     com.footballmanagergamesimulator.regent.market.core.MarketRiskClass riskClass,
                                     int horizonDays, java.math.BigDecimal confidence, java.math.BigDecimal risk,
                                     java.math.BigDecimal trailingReturn, java.math.BigDecimal observedVolatility,
                                     String explanation, String modelVersion) { }
}
