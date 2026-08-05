package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanSocialFeedServiceTest {

    @Test
    void heavyDefeatCreatesHarshButNonThreateningSupporterDiscussion() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FanSocialFeedService service = new FanSocialFeedService(inbox);

        service.publishPostMatchPosts(86, "Sherlock FC", "Yu Gi Oh", 0, 4,
                "National League", 8, 12);

        ArgumentCaptor<ManagerInbox> posts = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox, times(4)).save(posts.capture());
        assertThat(posts.getAllValues()).allSatisfy(post -> {
            assertThat(post.getCategory()).isEqualTo(FanSocialFeedService.CATEGORY);
            assertThat(post.isRead()).isTrue();
            assertThat(post.getTitle()).startsWith("@");
        });
        assertThat(posts.getAllValues()).extracting(ManagerInbox::getContent)
                .anySatisfy(content -> assertThat(content).startsWith("HARSH\n").contains("embarrassing"))
                .anySatisfy(content -> assertThat(content).startsWith("LOYAL\n"));
    }

    @Test
    void criticalFinancesCreateBoardAccountabilityPosts() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FanSocialFeedService service = new FanSocialFeedService(inbox);

        service.publishFinancialPosts(86, "Sherlock FC", 8, 62, "CRITICAL");

        ArgumentCaptor<ManagerInbox> posts = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox, times(3)).save(posts.capture());
        assertThat(posts.getAllValues()).extracting(ManagerInbox::getContent)
                .anySatisfy(content -> assertThat(content).contains("board").contains("full explanation"));
    }

    @Test
    void anExistingPostIsNotDuplicated() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        when(inbox.existsByTeamIdAndDeduplicationKey(eq(86L), anyString())).thenReturn(true);
        FanSocialFeedService service = new FanSocialFeedService(inbox);

        service.publishPostMatchPosts(86, "Sherlock FC", "Yu Gi Oh", 2, 1,
                "National League", 8, 12);

        verify(inbox, never()).save(any());
    }
}
