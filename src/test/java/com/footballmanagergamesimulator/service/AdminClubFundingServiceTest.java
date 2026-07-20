package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminClubFundingServiceTest {

    private AdminClubFundingService service;
    private TeamRepository teams;
    private FinanceService finances;
    private ManagerInboxRepository inboxes;
    private UserContext userContext;

    @BeforeEach
    void setUp() {
        service = new AdminClubFundingService();
        teams = mock(TeamRepository.class);
        finances = mock(FinanceService.class);
        inboxes = mock(ManagerInboxRepository.class);
        userContext = mock(UserContext.class);
        GameCalendarRepository calendars = mock(GameCalendarRepository.class);
        RoundRepository rounds = mock(RoundRepository.class);

        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(12);
        calendar.setCurrentDay(47);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        Round round = new Round();
        round.setSeason(12);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(inboxes.save(any(ManagerInbox.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "roundRepository", rounds);
        ReflectionTestUtils.setField(service, "gameCalendarRepository", calendars);
        ReflectionTestUtils.setField(service, "financeService", finances);
        ReflectionTestUtils.setField(service, "managerInboxRepository", inboxes);
        ReflectionTestUtils.setField(service, "userContext", userContext);
    }

    @Test
    void fundingOptionsExposeStableCodesAndAccountingCategories() {
        List<AdminClubFundingService.FundingOption> options = service.options();

        assertTrue(options.stream().anyMatch(option -> option.code().equals("BENEFACTOR")
                && option.category().equals("OWNER_INJECTION")));
        assertTrue(options.stream().anyMatch(option -> option.code().equals("NEW_SPONSOR")
                && option.category().equals("SPONSORSHIP")));
        assertTrue(options.size() >= 7);
        assertTrue(service.withdrawalOptions().stream().anyMatch(option ->
                option.code().equals("REGULATORY_FINE") && option.category().equals("FINES")));
    }

    @Test
    void addsSponsorFundingToLedgerAndNotifiesHumanManager() {
        Team team = new Team();
        team.setId(9L);
        team.setName("Sherlock FC");
        team.setTotalFinances(20_000_000L);
        team.setTransferBudget(5_000_000L);
        when(teams.findById(9L)).thenReturn(Optional.of(team));
        when(userContext.isHumanTeam(9L)).thenReturn(true);
        doAnswer(invocation -> {
            long amount = invocation.getArgument(5);
            team.setTotalFinances(team.getTotalFinances() + amount);
            team.setTransferBudget(team.getTransferBudget() + 6_000_000L);
            return null;
        }).when(finances).recordTransaction(9L, 12, 47, "SPONSORSHIP",
                "New sponsorship agreement — stadium naming rights", 10_000_000L);

        AdminClubFundingService.FundingResult result = service.addFunding(
                new AdminClubFundingService.FundingCommand(
                        9L, 10_000_000L, "new_sponsor", "  stadium   naming rights "));

        assertEquals(30_000_000L, result.totalFinances());
        assertEquals(11_000_000L, result.transferBudget());
        assertEquals(6_000_000L, result.transferBudgetAdded());
        assertEquals("SPONSORSHIP", result.category());
        assertEquals(12, result.season());
        assertEquals(47, result.day());
        verify(finances).recordTransaction(9L, 12, 47, "SPONSORSHIP",
                "New sponsorship agreement — stadium naming rights", 10_000_000L);

        ArgumentCaptor<ManagerInbox> message = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inboxes).save(message.capture());
        assertEquals("New sponsorship agreement", message.getValue().getTitle());
        assertEquals("finance", message.getValue().getCategory());
        assertTrue(message.getValue().getContent().contains("€10,000,000"));
    }

    @Test
    void rejectsUnknownReasonsBeforeWritingAnything() {
        Team team = new Team();
        team.setId(9L);
        when(teams.findById(9L)).thenReturn(Optional.of(team));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addFunding(new AdminClubFundingService.FundingCommand(
                        9L, 100L, "lottery", null)));

        assertEquals("Unknown funding reason: lottery", error.getMessage());
    }

    @Test
    void removesMoneyAsExpenseWithoutChangingTransferBudget() {
        Team team = new Team();
        team.setId(9L);
        team.setName("Sherlock FC");
        team.setTotalFinances(20_000_000L);
        team.setTransferBudget(5_000_000L);
        when(teams.findById(9L)).thenReturn(Optional.of(team));
        when(userContext.isHumanTeam(9L)).thenReturn(true);
        doAnswer(invocation -> {
            long amount = invocation.getArgument(5);
            team.setTotalFinances(team.getTotalFinances() - amount);
            return null;
        }).when(finances).recordExpense(9L, 12, 47, "FINES",
                "Regulatory fine — financial fair play", 3_000_000L);

        AdminClubFundingService.FundingResult result = service.removeFunding(
                new AdminClubFundingService.FundingCommand(
                        9L, 3_000_000L, "regulatory_fine", "financial fair play"));

        assertEquals(17_000_000L, result.totalFinances());
        assertEquals(5_000_000L, result.transferBudget());
        assertEquals(0L, result.transferBudgetAdded());
        assertEquals("FINES", result.category());
        verify(finances).recordExpense(9L, 12, 47, "FINES",
                "Regulatory fine — financial fair play", 3_000_000L);

        ArgumentCaptor<ManagerInbox> message = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inboxes).save(message.capture());
        assertEquals("Regulatory fine", message.getValue().getTitle());
        assertTrue(message.getValue().getContent().contains("Amount removed: €3,000,000"));
        assertTrue(message.getValue().getContent().contains("Transfer budget unchanged"));
    }

    @Test
    void rejectsNonPositiveAndUnsafeAmounts() {
        assertEquals("amount must be greater than 0", assertThrows(IllegalArgumentException.class,
                () -> service.addFunding(new AdminClubFundingService.FundingCommand(
                        9L, 0L, "OWNER", null))).getMessage());
        assertEquals("amount cannot exceed 10000000000000", assertThrows(IllegalArgumentException.class,
                () -> service.addFunding(new AdminClubFundingService.FundingCommand(
                        9L, 10_000_000_000_001L, "OWNER", null))).getMessage());
    }
}
