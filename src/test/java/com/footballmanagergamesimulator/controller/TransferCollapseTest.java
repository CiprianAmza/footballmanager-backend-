package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferCollapseTest {

    private final TransferOfferController market = new TransferOfferController();
    private final TransferOffer offer = new TransferOffer();

    @BeforeEach
    void mocks() {
        market.transferOfferRepository = mock(TransferOfferRepository.class);
        market.roundRepository = mock(RoundRepository.class);
        market.managerInboxRepository = mock(ManagerInboxRepository.class);
        when(market.roundRepository.findById(1L)).thenReturn(Optional.of(new Round()));
        offer.setId(5);
        offer.setFromTeamId(7);
        offer.setPlayerName("Vrel");
        when(market.transferOfferRepository.findById(5L)).thenReturn(Optional.of(offer));
    }

    @Test
    @DisplayName("""
            GIVEN a fee agreement still standing
            WHEN personal terms fail
            THEN it collapses and I get one "Transfer Collapsed" message
            """)
    void aStandingAgreementCollapsesOnce() {
        offer.setStatus(TransferOffer.STATUS_FEE_AGREED);

        market.collapse(5, "the agent walked away");

        assertEquals(TransferOffer.STATUS_TERMS_FAILED, offer.getStatus());
        verify(market.managerInboxRepository, times(1)).save(any(ManagerInbox.class));
    }

    @Test
    @DisplayName("""
            GIVEN a fee agreement already closed (the AI signed the player and my bid was closed as lost)
            WHEN the talks are then cancelled and ask for a collapse
            THEN nothing changes and no second message is sent
            """)
    void aClosedAgreementIsLeftAlone() {
        offer.setStatus(TransferOffer.STATUS_TERMS_FAILED);

        market.collapse(5, "the deal is no longer possible");

        verify(market.transferOfferRepository, never()).save(any());
        verifyNoInteractions(market.managerInboxRepository);
    }
}
