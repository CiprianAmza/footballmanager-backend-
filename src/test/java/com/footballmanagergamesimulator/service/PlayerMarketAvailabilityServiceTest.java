package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerMarketAvailabilityServiceTest {

    @Test
    void combinesCurrentSeasonTransfersLoansAndOlderActiveLoans() {
        HumanRepository humans = mock(HumanRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        LoanRepository loans = mock(LoanRepository.class);
        RoundRepository rounds = mock(RoundRepository.class);
        PlayerMarketAvailabilityService service =
                new PlayerMarketAvailabilityService(humans, transfers, loans, rounds);

        Round round = new Round();
        round.setSeason(8);
        Transfer transfer = new Transfer();
        transfer.setPlayerId(10L);
        Loan seasonLoan = new Loan();
        seasonLoan.setPlayerId(20L);
        Loan activeLoan = new Loan();
        activeLoan.setPlayerId(30L);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(transfers.findAllBySeasonNumber(8)).thenReturn(List.of(transfer));
        when(loans.findAllBySeasonNumber(8)).thenReturn(List.of(seasonLoan));
        when(loans.findAllByStatus("active")).thenReturn(List.of(activeLoan, seasonLoan));

        assertThat(service.unavailablePlayerIds()).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void exposesTransferredAndActiveLoanStateWithClubContext() {
        HumanRepository humans = mock(HumanRepository.class);
        TransferRepository transfers = mock(TransferRepository.class);
        LoanRepository loans = mock(LoanRepository.class);
        RoundRepository rounds = mock(RoundRepository.class);
        PlayerMarketAvailabilityService service =
                new PlayerMarketAvailabilityService(humans, transfers, loans, rounds);
        Round round = new Round(); round.setSeason(4);
        Transfer transfer = new Transfer(); transfer.setPlayerId(10); transfer.setSellTeamId(1);
        transfer.setSellTeamName("Old Club"); transfer.setBuyTeamId(2); transfer.setBuyTeamName("New Club");
        Loan loan = new Loan(); loan.setPlayerId(20); loan.setSeasonNumber(4); loan.setStatus("active");
        loan.setParentTeamId(3); loan.setParentTeamName("Parent Club");
        loan.setLoanTeamId(4); loan.setLoanTeamName("Loan Club");
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(transfers.findAllBySeasonNumber(4)).thenReturn(List.of(transfer));
        when(loans.findAllBySeasonNumber(4)).thenReturn(List.of(loan));
        when(loans.findAllByStatus("active")).thenReturn(List.of(loan));

        var states = service.currentSeasonStates();

        assertThat(states.get(10L).status()).isEqualTo("TRANSFERRED_THIS_SEASON");
        assertThat(states.get(10L).transferFromTeamName()).isEqualTo("Old Club");
        assertThat(states.get(20L).status()).isEqualTo("LOANED");
        assertThat(states.get(20L).parentTeamName()).isEqualTo("Parent Club");
        assertThat(states.get(20L).loanTeamName()).isEqualTo("Loan Club");
    }

    @Test
    void positionsComeFromTheDistinctActivePlayerQuery() {
        HumanRepository humans = mock(HumanRepository.class);
        when(humans.findDistinctActivePlayerPositions()).thenReturn(List.of("AMC", "DM", "GK"));
        PlayerMarketAvailabilityService service = new PlayerMarketAvailabilityService(
                humans, mock(TransferRepository.class), mock(LoanRepository.class), mock(RoundRepository.class));

        assertThat(service.activePlayerPositions()).containsExactly("AMC", "DM", "GK");
    }
}
