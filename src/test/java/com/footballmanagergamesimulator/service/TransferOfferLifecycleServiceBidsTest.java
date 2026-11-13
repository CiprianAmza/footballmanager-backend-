package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferOfferLifecycleServiceBidsTest {

    @Mock private TransferOfferRepository offers;
    @Mock private ManagerInboxRepository inbox;
    @InjectMocks private TransferOfferLifecycleService lifecycle;

    @Test
    @DisplayName("""
            GIVEN a player who just left his club, with an AI offer pending, my fee agreed for him and another
             manager's fee talks
            WHEN his active offers are removed
            THEN the AI offer is deleted, my fee agreement collapses (with a message to me) and the talks end;
             both bids are kept, closed, instead of staying live for ever
            """)
    void aPlayerWhoMovedOnEndsTheManagersBidsToo() {
        TransferOffer pending = offer(1, "pending", 0);
        TransferOffer agreed = offer(2, TransferOffer.STATUS_FEE_AGREED, 40);
        TransferOffer talks = offer(3, TransferOffer.STATUS_TALKS, 41);
        when(offers.findAllByPlayerIdInAndStatusIn(anyCollection(), argThat(statuses -> statuses != null && statuses.contains("pending"))))
                .thenReturn(List.of(pending));
        when(offers.findAllByPlayerIdInAndStatusIn(anyCollection(),
                argThat(statuses -> statuses != null && statuses.contains(TransferOffer.STATUS_FEE_AGREED))))
                .thenReturn(List.of(agreed, talks));

        int removed = lifecycle.removeActiveOffersForPlayers(Set.of(9L));

        assertEquals(3, removed);
        verify(offers).deleteAllInBatch(List.of(pending));
        assertEquals(TransferOffer.STATUS_TERMS_FAILED, agreed.getStatus());
        assertEquals(TransferOffer.STATUS_REJECTED, talks.getStatus());
        verify(offers).saveAll(List.of(agreed, talks));
        ArgumentCaptor<ManagerInbox> note = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox, times(1)).save(note.capture());
        assertEquals(40, note.getValue().getTeamId());
        assertEquals("Transfer Collapsed", note.getValue().getTitle());
    }

    @Test
    @DisplayName("""
            GIVEN a player with no live offers
            WHEN his active offers are removed
            THEN nothing is deleted, saved or sent
            """)
    void nothingToClose() {
        when(offers.findAllByPlayerIdInAndStatusIn(anyCollection(), any())).thenReturn(List.of());

        assertEquals(0, lifecycle.removeActiveOffersForPlayers(Set.of(9L)));

        verify(offers, never()).deleteAllInBatch(any());
        verify(offers, never()).saveAll(any());
        verifyNoInteractions(inbox);
    }

    private static TransferOffer offer(long id, String status, long fromTeamId) {
        TransferOffer offer = new TransferOffer();
        offer.setId(id);
        offer.setPlayerId(9);
        offer.setPlayerName("Vrel");
        offer.setStatus(status);
        offer.setFromTeamId(fromTeamId);
        offer.setDirection("outgoing");
        return offer;
    }
}
