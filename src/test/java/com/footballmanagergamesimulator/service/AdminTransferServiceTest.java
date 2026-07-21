package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AdminPlayerMovement;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.AdminPlayerMovementRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminTransferServiceTest {

    private AdminTransferService service;
    private AdminPlayerMovementRepository movements;
    private HumanRepository humans;
    private TeamRepository teams;
    private TransferRepository transfers;
    private LoanRepository loans;
    private MatchSimulationOrchestrator matchSimulation;
    private TransferOfferLifecycleService transferOfferLifecycleService;

    @BeforeEach
    void setUp() {
        service = new AdminTransferService();
        movements = mock(AdminPlayerMovementRepository.class);
        humans = mock(HumanRepository.class);
        teams = mock(TeamRepository.class);
        transfers = mock(TransferRepository.class);
        loans = mock(LoanRepository.class);
        matchSimulation = mock(MatchSimulationOrchestrator.class);
        transferOfferLifecycleService = mock(TransferOfferLifecycleService.class);
        RoundRepository rounds = mock(RoundRepository.class);
        Round round = new Round();
        round.setSeason(9);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(movements.save(any(AdminPlayerMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(service, "movementRepository", movements);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "transferRepository", transfers);
        ReflectionTestUtils.setField(service, "loanRepository", loans);
        ReflectionTestUtils.setField(service, "roundRepository", rounds);
        ReflectionTestUtils.setField(service, "financeService", mock(FinanceService.class));
        ReflectionTestUtils.setField(service, "matchSimulationOrchestrator", matchSimulation);
        ReflectionTestUtils.setField(service, "managerInboxRepository", mock(ManagerInboxRepository.class));
        ReflectionTestUtils.setField(service, "userContext", mock(UserContext.class));
        ReflectionTestUtils.setField(service, "transferOfferLifecycleService", transferOfferLifecycleService);
    }

    @Test
    void immediatePermanentTransferMovesPlayerAndKeepsAuditRow() {
        Team source = team(1, "Source");
        Team destination = team(2, "Destination");
        Human player = player(10, source.getId(), 100, 15);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(source.getId())).thenReturn(Optional.of(source));
        when(teams.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(loans.findAllByPlayerIdAndStatus(player.getId(), "active")).thenReturn(List.of());

        AdminPlayerMovement movement = service.create(new AdminTransferService.MovementCommand(
                "PERMANENT", player.getId(), destination.getId(), 5_000L,
                250L, 3, null, null, "NOW", null));

        assertEquals(destination.getId(), player.getTeamId());
        assertEquals(250, player.getWage());
        assertEquals(12, player.getContractEndSeason());
        assertEquals(AdminTransferService.COMPLETED, movement.getStatus());
        verify(transfers).save(any(Transfer.class));
        verify(matchSimulation).invalidateRatingCache(source.getId());
        verify(matchSimulation).invalidateRatingCache(destination.getId());
        verify(transferOfferLifecycleService).removeActiveOffersForPlayer(player.getId());
    }

    @Test
    void scheduledTransferWaitsForSelectedSeason() {
        Team source = team(1, "Source");
        Team destination = team(2, "Destination");
        Human player = player(10, source.getId(), 100, 15);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(source.getId())).thenReturn(Optional.of(source));
        when(teams.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(loans.findAllByPlayerIdAndStatus(player.getId(), "active")).thenReturn(List.of());

        AdminPlayerMovement movement = service.create(new AdminTransferService.MovementCommand(
                "PERMANENT", player.getId(), destination.getId(), 5_000L,
                250L, 3, null, null, "START_OF_SEASON", 11));

        assertEquals(source.getId(), player.getTeamId());
        assertEquals(AdminTransferService.PENDING, movement.getStatus());
        assertEquals(11, movement.getExecutionSeason());
        verify(transfers, never()).save(any());

        when(movements.findAllByStatusAndExecutionSeasonLessThanEqualOrderByIdAsc(
                AdminTransferService.PENDING, 11)).thenReturn(List.of(movement));
        assertEquals(1, service.executeScheduledForSeason(11));
        assertEquals(destination.getId(), player.getTeamId());
        assertEquals(AdminTransferService.COMPLETED, movement.getStatus());
        verify(transfers).save(any());
    }

    @Test
    void loanCannotOutlastParentContract() {
        Team source = team(1, "Source");
        Team destination = team(2, "Destination");
        Human player = player(10, source.getId(), 100, 11);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(source.getId())).thenReturn(Optional.of(source));
        when(teams.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(loans.findAllByPlayerIdAndStatus(player.getId(), "active")).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AdminTransferService.MovementCommand(
                        "LOAN", player.getId(), destination.getId(), 0L,
                        null, null, 3, 0, "NOW", null)));

        assertEquals("Loan can last at most 2 season(s), matching the player's remaining contract",
                error.getMessage());
    }

    @Test
    void freeAgentCanBeSignedWithAdminSalaryAndContract() {
        Team destination = team(2, "Destination");
        Human player = player(10, 0, 100, 0);
        player.setTeamId(null);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(loans.findAllByPlayerIdAndStatus(player.getId(), "active")).thenReturn(List.of());

        service.create(new AdminTransferService.MovementCommand(
                "FREE_AGENT", player.getId(), destination.getId(), 99_999L,
                300L, 4, null, null, "NOW", null));

        assertEquals(destination.getId(), player.getTeamId());
        assertEquals(300, player.getWage());
        assertEquals(13, player.getContractEndSeason());
        ArgumentCaptor<Transfer> transfer = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(transfer.capture());
        assertEquals(0, transfer.getValue().getSellTeamId());
        assertEquals(0, transfer.getValue().getPlayerTransferValue());
    }

    @Test
    void validMultiSeasonLoanPersistsExactStartAndEndSeasons() {
        Team source = team(1, "Source");
        Team destination = team(2, "Destination");
        Human player = player(10, source.getId(), 100, 12);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));
        when(teams.findById(source.getId())).thenReturn(Optional.of(source));
        when(teams.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(loans.findAllByPlayerIdAndStatus(player.getId(), "active")).thenReturn(List.of());

        service.create(new AdminTransferService.MovementCommand(
                "LOAN", player.getId(), destination.getId(), 500L,
                null, null, 2, 25, "NOW", null));

        ArgumentCaptor<Loan> loan = ArgumentCaptor.forClass(Loan.class);
        verify(loans).save(loan.capture());
        assertEquals(9, loan.getValue().getStartSeason());
        assertEquals(10, loan.getValue().getEndSeason());
        assertEquals(25, loan.getValue().getParentWageContribution());
        assertEquals(destination.getId(), player.getTeamId());
    }

    @Test
    void editorProtectedPlayerCannotBeMovedEvenByAdminTransferQueue() {
        Team source = team(1, "Source");
        Team destination = team(2, "Destination");
        Human player = player(10, source.getId(), 100, 15);
        player.setWillNeverLeave(true);
        when(humans.findById(player.getId())).thenReturn(Optional.of(player));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AdminTransferService.MovementCommand(
                        "PERMANENT", player.getId(), destination.getId(), 5_000L,
                        250L, 3, null, null, "NOW", null)));

        assertEquals("Player is editor-protected and will never leave their club", error.getMessage());
        verify(transfers, never()).save(any());
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setTransferBudget(1_000_000);
        team.setSalaryBudget(10_000);
        return team;
    }

    private Human player(long id, long teamId, long wage, int contractEndSeason) {
        Human player = new Human();
        player.setId(id);
        player.setName("Player");
        player.setTypeId(1);
        player.setTeamId(teamId);
        player.setWage(wage);
        player.setContractEndSeason(contractEndSeason);
        player.setRating(200);
        return player;
    }
}
