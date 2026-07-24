package com.footballmanagergamesimulator.economy;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase4b-adviser;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    private User register(String username, CareerRole role, long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, role, wealth));
    }
}
