package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ContractNegotiation;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ContractNegotiationRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import com.footballmanagergamesimulator.service.AgentNegotiation.Status;
import com.footballmanagergamesimulator.service.ContractNegotiationService.NegotiationException;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The checks before a contract is signed: the player still mine, the window open, the money there. */
class ContractNegotiationRulesTest {

    private static final long MY_CLUB = 7;
    private static final long SELLER = 8;
    private static final long PLAYER = 9;
    private static final long OFFER = 5;

    private final ContractNegotiationService service = new ContractNegotiationService();
    private final Human player = new Human();
    private final Team myClub = new Team();

    @BeforeEach
    void mocks() {
        service.negotiationRepository = mock(ContractNegotiationRepository.class);
        service.humanRepository = mock(HumanRepository.class);
        service.teamRepository = mock(TeamRepository.class);
        service.transferOfferRepository = mock(TransferOfferRepository.class);
        service.transferHandler = mock(AgreedTransferHandler.class);
        service.plannedTransferService = mock(PlannedTransferService.class);
        when(service.negotiationRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        player.setId(PLAYER);
        player.setName("Vrel");
        when(service.humanRepository.findById(PLAYER)).thenReturn(Optional.of(player));
        myClub.setId(MY_CLUB);
        when(service.teamRepository.findById(MY_CLUB)).thenReturn(Optional.of(myClub));
    }

    @Test
    @DisplayName("""
            GIVEN renewal talks with my player
            WHEN he is sold to another club and I then make my offer
            THEN the talks are cancelled and nothing is signed: no new wage for a player at another club,
             no money moved from my budgets
            """)
    void aRenewalIsNotSignedForAPlayerWhoWasSold() {
        ContractNegotiation n = negotiation(ContractNegotiation.KIND_RENEWAL);
        player.setTeamId(SELLER);

        ContractNegotiation after = service.offer(n.getId(), MY_CLUB, new Offer(2_000, 1, 0, 0));

        assertEquals(Status.CANCELLED, after.getStatus());
        verify(service.humanRepository, never()).save(any());
        verify(service.teamRepository, never()).save(any());
    }

    @Test
    @DisplayName("""
            GIVEN renewal talks with my player and 1,000 in the transfer budget
            WHEN I offer a signing bonus of 5,000
            THEN the offer is refused (BONUS_OVER_BUDGET) and the talks are unchanged
            """)
    void aSigningBonusMustFitTheBudget() {
        ContractNegotiation n = negotiation(ContractNegotiation.KIND_RENEWAL);
        player.setTeamId(MY_CLUB);
        myClub.setTransferBudget(1_000);

        NegotiationException refused = assertThrows(NegotiationException.class,
                () -> service.offer(n.getId(), MY_CLUB, new Offer(2_000, 1, 5_000, 0)));

        assertEquals("BONUS_OVER_BUDGET", refused.getMessage());
        assertEquals(Status.OPEN, n.getStatus());
        verify(service.negotiationRepository, never()).save(any());
    }

    @Test
    @DisplayName("""
            GIVEN a fee of 1,000,000 agreed with the selling club, and 500,000 left in my budget
            WHEN I open personal terms
            THEN they do not start (INSUFFICIENT_BUDGET) and the fee agreement collapses, instead of staying open
            """)
    void personalTermsDoNotStartWhenTheFeeCanNoLongerBePaid() {
        feeAgreed();
        player.setTeamId(SELLER);
        myClub.setTransferBudget(500_000);

        NegotiationException refused = assertThrows(NegotiationException.class,
                () -> service.startTransfer(OFFER, MY_CLUB));

        assertEquals("INSUFFICIENT_BUDGET", refused.getMessage());
        verify(service.transferHandler).collapse(eq(OFFER), contains("afford"));
    }

    @Test
    @DisplayName("""
            GIVEN a fee agreed for a player who has since left the selling club
            WHEN I open personal terms
            THEN they do not start (PLAYER_GONE) and the dead fee agreement collapses, so it no longer blocks new
             talks for him
            """)
    void personalTermsForAPlayerWhoLeftCollapseTheAgreement() {
        feeAgreed();
        player.setTeamId(99L);
        myClub.setTransferBudget(5_000_000);

        NegotiationException refused = assertThrows(NegotiationException.class,
                () -> service.startTransfer(OFFER, MY_CLUB));

        assertEquals("PLAYER_GONE", refused.getMessage());
        verify(service.transferHandler).collapse(eq(OFFER), anyString());
    }

    @Test
    @DisplayName("""
            GIVEN personal terms in progress for a fee-agreed transfer
            WHEN the transfer window has closed and I make my offer
            THEN the talks are cancelled and the fee agreement collapses: no transfer outside the window
            """)
    void noTransferIsSignedAfterTheWindowCloses() {
        feeAgreed();
        player.setTeamId(SELLER);
        myClub.setTransferBudget(5_000_000);
        ContractNegotiation n = negotiation(ContractNegotiation.KIND_TRANSFER);
        n.setTransferOfferId(OFFER);
        n.setTransferFee(1_000_000);
        TransferMarketService market = mock(TransferMarketService.class);
        when(market.isOpen()).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<TransferMarketService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(market);
        service.transferMarket = provider;

        ContractNegotiation after = service.offer(n.getId(), MY_CLUB, new Offer(2_000, 3, 0, 0));

        assertEquals(Status.CANCELLED, after.getStatus());
        verify(service.transferHandler).collapse(eq(OFFER), anyString());
        verify(service.transferHandler, never()).complete(anyLong());
    }

    private ContractNegotiation negotiation(String kind) {
        ContractNegotiation n = new ContractNegotiation();
        n.setId(1);
        n.setKind(kind);
        n.setTeamId(MY_CLUB);
        n.setPlayerId(PLAYER);
        n.setStatus(Status.OPEN);
        when(service.negotiationRepository.findById(1L)).thenReturn(Optional.of(n));
        return n;
    }

    private void feeAgreed() {
        TransferOffer offer = new TransferOffer();
        offer.setId(OFFER);
        offer.setPlayerId(PLAYER);
        offer.setFromTeamId(MY_CLUB);
        offer.setToTeamId(SELLER);
        offer.setToTeamName("Kvar");
        offer.setDirection("outgoing");
        offer.setStatus(TransferOffer.STATUS_FEE_AGREED);
        offer.setOfferAmount(1_000_000);
        when(service.transferOfferRepository.findById(OFFER)).thenReturn(Optional.of(offer));
        when(service.negotiationRepository.findFirstByTransferOfferIdOrderByIdDesc(OFFER)).thenReturn(Optional.empty());
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
