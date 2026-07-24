package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class ChairmanInboxNotificationServiceTest {
    @Test
    void duplicateDeduplicationKeyIsAReplay() {
        ManagerInboxRepository repository = mock(ManagerInboxRepository.class);
        when(repository.existsByRecipientProfileIdAndDeduplicationKey(7L, "CHAIRMAN_WELCOME:7"))
                .thenReturn(false, true);
        ChairmanInboxNotificationService service = new ChairmanInboxNotificationService(repository);

        assertThat(service.notify(7L, 0L, 0, 0, "CHAIRMAN_WELCOME", "Welcome", "body", "CHAIRMAN_WELCOME:7"))
                .isTrue();
        assertThat(service.notify(7L, 0L, 0, 0, "CHAIRMAN_WELCOME", "Welcome", "body", "CHAIRMAN_WELCOME:7"))
                .isFalse();
        verify(repository, times(1)).save(any());
    }
}
