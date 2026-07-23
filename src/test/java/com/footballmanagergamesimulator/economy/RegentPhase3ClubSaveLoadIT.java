package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase3-save-load;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true",
        "regent.economy.chairman-starting-wealth-max=900000000000000000",
        "regent.club.minimum-protected-reserve=1000",
        "regent.club.protected-wage-months=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase3ClubSaveLoadIT {
    private static final long STARTING_WEALTH = 100_000_000_000L;

    @Autowired private GameController gameController;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserService userService;
    @Autowired private PersonProfileService profileService;
    @Autowired private RoundRepository roundRepository;
    @Autowired private CalendarService calendarService;
    @Autowired private TeamRepository teamRepository;
    @Autowired private PersonalAccountRepository accountRepository;
    @Autowired private PersonalAccountingService accountingService;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private PortfolioPositionRepository positionRepository;
    @Autowired private ClubCapTableStateRepository stateRepository;
    @Autowired private ClubFinancialObligationRepository obligationRepository;
    @Autowired private TakeoverService takeoverService;
    @Autowired private TakeoverQuoteRepository quoteRepository;
    @Autowired private TakeoverExecutionRepository executionRepository;
    @Autowired private ClubTreasuryService treasuryService;
    @Autowired private ClubCashTransferRepository transferRepository;

    @Test
    void v9RoundTripRestoresCapTableTakeoverTreasuryAndRetryBoundaries() throws Exception {
        Round round = roundRepository.findById(1L).orElseThrow();
        calendarService.getOrCreateCalendar((int) round.getSeason());
        Team team = teamRepository.findAll().stream().sorted(Comparator.comparingLong(Team::getId))
                .skip(60).findFirst().orElseThrow();
        PersonProfile chairman = profileService.requireForUser(userService.register(new RegisterRequest(
                "phase3-save", "phase3-save@example.com", "correct-password", "Phase 3 Save",
                CareerRole.CHAIRMAN, STARTING_WEALTH)));
        PersonalAccount account = accountRepository.findByProfileId(chairman.getId()).orElseThrow();

        ClubFinancialObligation obligation = new ClubFinancialObligation();
        obligation.setTeamId(team.getId());
        obligation.setCategory("SAVE_ROUND_TRIP");
        obligation.setAmountRemaining(321);
        obligation.setDueSeason(0);
        obligation.setDueDay(0);
        obligation.setRestrictsWithdrawal(false);
        obligation.setSettled(false);
        obligation = obligationRepository.saveAndFlush(obligation);

        TakeoverQuote quote = takeoverService.quote(chairman, team.getId(), "save-quote").quote();
        TakeoverExecution execution = takeoverService.execute(
                chairman, team.getId(), quote.getQuoteKey(), "save-takeover").execution();
        ClubCashTransfer transfer = treasuryService.transfer(chairman, team.getId(),
                ClubCashTransferDirection.INJECTION, 12_345, "save-injection").transfer();
        MarketInstrument instrument = instrumentRepository.findByTeamId(team.getId()).orElseThrow();
        long savedCash = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
        long savedClubCash = teamRepository.findById(team.getId()).orElseThrow().getTotalFinances();

        Map<String, Object> exported = objectMapper.readValue(
                objectMapper.writeValueAsBytes(gameController.exportGame()), new TypeReference<>() { });
        assertThat(exported.get("saveVersion")).isEqualTo(9);
        assertThat((List<?>) exported.get("clubCapTableStates")).isNotEmpty();
        assertThat((List<?>) exported.get("takeoverQuotes")).isNotEmpty();
        assertThat((List<?>) exported.get("takeoverExecutions")).isNotEmpty();
        assertThat((List<?>) exported.get("clubCashTransfers")).isNotEmpty();

        treasuryService.transfer(chairman, team.getId(), ClubCashTransferDirection.INJECTION,
                1, "after-save-mutation");
        assertThat(gameController.importGame(exported)).containsEntry("success", true);

        PersonalAccount restoredAccount = accountRepository.findById(account.getId()).orElseThrow();
        Team restoredTeam = teamRepository.findById(team.getId()).orElseThrow();
        MarketInstrument restoredInstrument = instrumentRepository.findById(instrument.getId()).orElseThrow();
        ClubCapTableState restoredState = stateRepository.findByTeamId(team.getId()).orElseThrow();
        assertThat(restoredAccount.getCashBalance()).isEqualTo(savedCash);
        assertThat(restoredTeam.getTotalFinances()).isEqualTo(savedClubCash);
        assertThat(restoredState.getControllingAccountId()).isEqualTo(account.getId());
        assertThat(restoredInstrument.getAvailableSupply()).isZero();
        assertThat(positionRepository.sumQuantityByInstrumentId(instrument.getId()))
                .isEqualTo(restoredInstrument.getTotalSupply());
        assertThat(quoteRepository.findById(quote.getId()).orElseThrow().getStatus())
                .isEqualTo(TakeoverQuoteStatus.EXECUTED);
        assertThat(executionRepository.findById(execution.getId())).isPresent();
        assertThat(transferRepository.findById(transfer.getId())).isPresent();
        assertThat(obligationRepository.findById(obligation.getId())).isPresent();
        assertThat(transferRepository.findByAccountIdAndIdempotencyKey(
                account.getId(), "after-save-mutation")).isEmpty();

        assertThat(takeoverService.execute(chairman, team.getId(), quote.getQuoteKey(), "save-takeover")
                .replayed()).isTrue();
        assertThat(treasuryService.transfer(chairman, team.getId(), ClubCashTransferDirection.INJECTION,
                12_345, "save-injection").replayed()).isTrue();
        accountingService.assertReconciled(account.getId());
    }
}
