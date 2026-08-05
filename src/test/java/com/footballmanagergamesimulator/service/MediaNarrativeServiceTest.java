package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MediaNarrativeServiceTest {

    @Test
    void heavyDefeatCreatesAnOriginalPressureStory() {
        ManagerInboxRepository repository = mock(ManagerInboxRepository.class);
        MediaNarrativeService service = new MediaNarrativeService(repository);

        service.publishPostMatchReaction(8L, "Orbit FC", 9L, "Nova United",
                0, 4, "Premier Division", 3, 12);

        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(repository).save(captor.capture());
        ManagerInbox message = captor.getValue();
        assertThat(message.getCategory()).isEqualTo("MEDIA_REACTION");
        assertThat(message.getTitle()).contains("under scrutiny");
        assertThat(message.getContent()).contains("Studio verdict:").contains("Supporters");
        assertThat(message.getDeduplicationKey()).isEqualTo("MEDIA_MATCH:3:12:8:9:0:4");
    }

    @Test
    void duplicateMatchStoryIsNotPublishedTwice() {
        ManagerInboxRepository repository = mock(ManagerInboxRepository.class);
        when(repository.existsByTeamIdAndDeduplicationKey(eq(8L), anyString())).thenReturn(true);
        MediaNarrativeService service = new MediaNarrativeService(repository);

        service.publishPostMatchReaction(8L, "Orbit FC", 9L, "Nova United",
                2, 1, "Premier Division", 3, 12);

        verify(repository, never()).save(any());
    }

    @Test
    void realOfferCreatesUnconfirmedRumourForBothClubs() {
        ManagerInboxRepository repository = mock(ManagerInboxRepository.class);
        MediaNarrativeService service = new MediaNarrativeService(repository);
        TransferOffer offer = new TransferOffer();
        offer.setId(41);
        offer.setPlayerId(99);
        offer.setPlayerName("Alex Prospect");
        offer.setFromTeamId(7);
        offer.setFromTeamName("Orbit FC");
        offer.setToTeamId(8);
        offer.setToTeamName("Nova United");
        offer.setOfferAmount(12_500_000);
        offer.setSeasonNumber(4);
        offer.setStatus("pending");

        service.publishTransferRumour(offer, 9);

        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ManagerInbox::getTeamId).containsExactly(7L, 8L);
        assertThat(captor.getAllValues()).allSatisfy(message -> {
            assertThat(message.getCategory()).isEqualTo("TRANSFER_RUMOUR");
            assertThat(message.getContent()).contains("Credibility:").contains("Neither club has publicly confirmed");
        });
    }
}
