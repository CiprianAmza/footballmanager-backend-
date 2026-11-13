package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.service.press.MatchReactionArticle;
import com.footballmanagergamesimulator.service.press.PressDeskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaNarrativeSignedStoryTest {

    private ManagerInboxRepository inbox;
    private PressDeskService desk;
    private MediaNarrativeService service;

    @BeforeEach
    void setUp() {
        inbox = mock(ManagerInboxRepository.class);
        desk = mock(PressDeskService.class);
        service = new MediaNarrativeService(inbox);
        ReflectionTestUtils.setField(service, "pressDesk", desk);
    }

    @Test
    @DisplayName("""
            GIVEN the press desk has a signed article for Orbit FC's 0-4 defeat
            WHEN the post-match reaction is published
            THEN the club's inbox gets the reporter's headline and text as a media reaction, under the same
             once-per-match key as before
            """)
    void signedArticleReachesTheInbox() {
        MatchReactionArticle.Facts facts = defeat();
        when(desk.matchReaction(facts)).thenReturn(Optional.of(
                new PressDeskService.Piece("MELTDOWN: Orbit FC torn apart", "MEDIA WATCH · By Dan Popa\n\n…", 5, "Dan Popa")));

        service.publishPostMatchReaction(facts);

        ManagerInbox message = saved();
        assertEquals("MELTDOWN: Orbit FC torn apart", message.getTitle());
        assertEquals("MEDIA WATCH · By Dan Popa\n\n…", message.getContent());
        assertEquals("MEDIA_REACTION", message.getCategory());
        assertEquals(8, message.getTeamId());
        assertEquals("MEDIA_MATCH:3:12:8:9:0:4", message.getDeduplicationKey());
    }

    @Test
    @DisplayName("""
            GIVEN the press desk has nothing for the match (no reporter in that league)
            WHEN the post-match reaction is published
            THEN the plain, unsigned text is published instead
            """)
    void withoutAReporterThePlainTextIsKept() {
        when(desk.matchReaction(any())).thenReturn(Optional.empty());

        service.publishPostMatchReaction(defeat());

        ManagerInbox message = saved();
        assertTrue(message.getTitle().contains("under scrutiny"), message.getTitle());
        assertTrue(message.getContent().contains("Studio verdict:"), message.getContent());
    }

    @Test
    @DisplayName("""
            GIVEN a rumour about offer 41 that the buying club (7) has already received and the selling club (8) has not,
             then one both have received
            WHEN the rumour is published each time
            THEN the first time the signed story goes to the selling club only; the second time the reporter is
             not even asked to write, so no fragment is used up
            """)
    void rumourIsWrittenOnlyForClubsThatLackIt() {
        TransferOffer offer = new TransferOffer();
        offer.setId(41);
        offer.setFromTeamId(7);
        offer.setToTeamId(8);
        offer.setSeasonNumber(4);
        when(inbox.existsByTeamIdAndDeduplicationKey(7, "TRANSFER_RUMOUR:41:7")).thenReturn(true);
        when(desk.transferRumour(offer, 9)).thenReturn(Optional.of(
                new PressDeskService.Piece("Orbit FC close in", "TRANSFER RUMOUR · By Radu Voss\n…", 12, "Radu Voss")));

        service.publishTransferRumour(offer, 9);

        ManagerInbox message = saved();
        assertEquals(8, message.getTeamId());
        assertEquals("Orbit FC close in", message.getTitle());
        assertEquals("TRANSFER_RUMOUR", message.getCategory());
        assertEquals("TRANSFER_RUMOUR:41:8", message.getDeduplicationKey());

        ManagerInboxRepository full = mock(ManagerInboxRepository.class);
        PressDeskService idle = mock(PressDeskService.class);
        MediaNarrativeService second = new MediaNarrativeService(full);
        ReflectionTestUtils.setField(second, "pressDesk", idle);
        when(full.existsByTeamIdAndDeduplicationKey(7, "TRANSFER_RUMOUR:41:7")).thenReturn(true);
        when(full.existsByTeamIdAndDeduplicationKey(8, "TRANSFER_RUMOUR:41:8")).thenReturn(true);

        second.publishTransferRumour(offer, 9);

        verify(idle, never()).transferRumour(any(), anyInt());
        verify(full, never()).save(any());
    }

    private ManagerInbox saved() {
        ArgumentCaptor<ManagerInbox> captor = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox).save(captor.capture());
        return captor.getValue();
    }

    private static MatchReactionArticle.Facts defeat() {
        return new MatchReactionArticle.Facts(8, "Orbit FC", 9, "Nova United", 0, 4, 3, "Premier Division", 3, 12, 8, 9);
    }
}
