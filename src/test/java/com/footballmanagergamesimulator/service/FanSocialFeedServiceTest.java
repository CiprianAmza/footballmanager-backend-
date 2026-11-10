package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;

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
        verify(inbox, times(5)).save(posts.capture());
        assertThat(posts.getAllValues()).allSatisfy(post -> {
            assertThat(post.getCategory()).isEqualTo(FanSocialFeedService.CATEGORY);
            assertThat(post.isRead()).isTrue();
            assertThat(post.getTitle()).startsWith("@");
        });
        assertThat(posts.getAllValues()).extracting(ManagerInbox::getContent)
                .anySatisfy(content -> assertThat(content).startsWith("HARSH\n"))
                .anySatisfy(content -> assertThat(content).startsWith("LOYAL\n"));
        assertThat(posts.getAllValues()).extracting(ManagerInbox::getContent)
                .doesNotHaveDuplicates()
                .allSatisfy(content -> assertThat(content).doesNotContain("{team}", "{opponent}"));
    }

    @Test
    void criticalFinancesCreateBoardAccountabilityPosts() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FanSocialFeedService service = new FanSocialFeedService(inbox);

        service.publishFinancialPosts(86, "Sherlock FC", 8, 62, "CRITICAL");

        ArgumentCaptor<ManagerInbox> posts = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox, times(4)).save(posts.capture());
        assertThat(posts.getAllValues()).extracting(ManagerInbox::getContent)
                .anySatisfy(content -> assertThat(content).startsWith("ANGRY\n"))
                .anySatisfy(content -> assertThat(content).startsWith("LOYAL\n"));
    }

    @Test
    void recurringWinsDoNotRepeatTheSameFiveMessages() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        FanSocialFeedService service = new FanSocialFeedService(inbox);
        for (int day = 40; day < 50; day++) {
            service.publishPostMatchPosts(86, "Sherlock FC", "Kuntuna", 2, 1,
                    "National League", 8, day);
        }

        ArgumentCaptor<ManagerInbox> posts = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(inbox, times(50)).save(posts.capture());
        List<String> bodies = posts.getAllValues().stream()
                .map(ManagerInbox::getContent)
                .map(content -> content.split("\\n", 3)[2])
                .toList();
        assertThat(bodies.stream().distinct().count()).isGreaterThan(35);
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

    @Test
    void refreshKeepsHistoricalContextAndIsIdempotent() {
        ManagerInboxRepository inbox = mock(ManagerInboxRepository.class);
        ManagerInbox oldPost = new ManagerInbox();
        oldPost.setTeamId(86);
        oldPost.setCategory(FanSocialFeedService.CATEGORY);
        oldPost.setDeduplicationKey("SOCIAL_FEED:2:34:86:MATCH:Moara cu Noroc:6:4:0");
        oldPost.setContent("POSITIVE\nLiterature League: Sherlock FC 6-4 Moara cu Noroc\nThree points and a proper shift.");
        when(inbox.findAllByTeamIdAndCategoryOrderByIdDesc(86L, FanSocialFeedService.CATEGORY))
                .thenReturn(List.of(oldPost));
        FanSocialFeedService service = new FanSocialFeedService(inbox);

        assertThat(service.refreshExistingPosts(86)).isEqualTo(1);
        assertThat(oldPost.getContent()).startsWith("POSITIVE\nLiterature League: Sherlock FC 6-4 Moara cu Noroc\n")
                .doesNotContain("Three points and a proper shift");
        assertThat(service.refreshExistingPosts(86)).isZero();
        verify(inbox, times(1)).save(oldPost);
    }
}
