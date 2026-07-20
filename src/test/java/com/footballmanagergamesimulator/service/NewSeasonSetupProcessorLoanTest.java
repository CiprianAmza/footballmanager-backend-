package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewSeasonSetupProcessorLoanTest {

    private NewSeasonSetupProcessor processor;
    private LoanRepository loans;
    private HumanRepository humans;
    private TeamRepository teams;

    @BeforeEach
    void setUp() {
        processor = new NewSeasonSetupProcessor();
        loans = mock(LoanRepository.class);
        humans = mock(HumanRepository.class);
        teams = mock(TeamRepository.class);
        ReflectionTestUtils.setField(processor, "loanRepository", loans);
        ReflectionTestUtils.setField(processor, "humanRepository", humans);
        ReflectionTestUtils.setField(processor, "teamRepository", teams);
        ReflectionTestUtils.setField(processor, "financeService", mock(FinanceService.class));
        ReflectionTestUtils.setField(processor, "userContext", mock(UserContext.class));
        ReflectionTestUtils.setField(processor, "managerInboxRepository", mock(ManagerInboxRepository.class));
    }

    @Test
    void multiSeasonLoanDoesNotReturnBeforeItsEndSeason() {
        Loan loan = loan(9, 10);
        when(loans.findAllByStatus("active")).thenReturn(List.of(loan));

        processor.processLoanReturns(9);

        verify(humans, never()).findById(loan.getPlayerId());
        assertEquals("active", loan.getStatus());
    }

    @Test
    void loanReturnsAfterItsFinalSeasonAndRestoresOnlyBorrowerWageShare() {
        Loan loan = loan(9, 10);
        loan.setParentWageContribution(25);
        Human player = new Human();
        player.setId(loan.getPlayerId());
        player.setTeamId(loan.getLoanTeamId());
        player.setWage(100);
        Team parent = team(1, 25);
        Team borrower = team(2, 75);
        when(loans.findAllByStatus("active")).thenReturn(List.of(loan));
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(teams.findById(borrower.getId())).thenReturn(Optional.of(borrower));

        processor.processLoanReturns(10);

        assertEquals(parent.getId(), player.getTeamId());
        assertEquals(100, parent.getSalaryBudget());
        assertEquals(0, borrower.getSalaryBudget());
        assertEquals("completed", loan.getStatus());
        verify(loans).save(loan);
    }

    private Loan loan(int startSeason, int endSeason) {
        Loan loan = new Loan();
        loan.setPlayerId(7);
        loan.setParentTeamId(1);
        loan.setParentTeamName("Parent");
        loan.setLoanTeamId(2);
        loan.setLoanTeamName("Borrower");
        loan.setStartSeason(startSeason);
        loan.setEndSeason(endSeason);
        loan.setStatus("active");
        return loan;
    }

    private Team team(long id, long salaryBudget) {
        Team team = new Team();
        team.setId(id);
        team.setSalaryBudget(salaryBudget);
        return team;
    }
}
