package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FastForwardServiceTest {

    private final FastForwardService service = new FastForwardService();
    private final GameAdvanceService gameAdvanceService = mock(GameAdvanceService.class);
    private final GameCalendarRepository calendarRepository = mock(GameCalendarRepository.class);

    FastForwardServiceTest() {
        ReflectionTestUtils.setField(service, "gameAdvanceService", gameAdvanceService);
        ReflectionTestUtils.setField(service, "gameCalendarRepository", calendarRepository);
    }

    @AfterEach
    void closeExecutor() {
        service.shutdown();
    }

    @Test
    void completesInBackgroundWhenTheRequestedSeasonTransitionIsReached() throws Exception {
        AtomicReference<GameCalendar> current = new AtomicReference<>(calendar(1, 359));
        CountDownLatch transitioned = new CountDownLatch(1);
        when(gameAdvanceService.isAlwaysContinueActive()).thenReturn(true);
        when(calendarRepository.findTopByOrderBySeasonDesc())
                .thenAnswer(invocation -> Optional.of(current.get()));
        doAnswer(invocation -> {
            GameCalendar before = current.get();
            if (before.getCurrentDay() >= 360) {
                current.set(calendar(before.getSeason() + 1, 1));
                transitioned.countDown();
            } else {
                current.set(calendar(before.getSeason(), before.getCurrentDay() + 1));
            }
            return Map.of("paused", false);
        }).when(gameAdvanceService).advance(anyInt());

        FastForwardService.FastForwardStatus started = service.start(1, 1);

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(transitioned.await(2, TimeUnit.SECONDS)).isTrue();
        FastForwardService.FastForwardStatus finished = waitForTerminalStatus();
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.percent()).isEqualTo(100);
        assertThat(finished.currentSeason()).isEqualTo(2);
        assertThat(finished.cancellable()).isFalse();
    }

    @Test
    void rejectsUnattendedRunWhenAlwaysContinueIsDisabled() {
        when(gameAdvanceService.isAlwaysContinueActive()).thenReturn(false);

        assertThatThrownBy(() -> service.start(1, 30))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Always Continue");
    }

    private FastForwardService.FastForwardStatus waitForTerminalStatus() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            FastForwardService.FastForwardStatus status = service.getStatus();
            if (!"RUNNING".equals(status.status())) return status;
            Thread.sleep(10);
        }
        return service.getStatus();
    }

    private GameCalendar calendar(int season, int day) {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(season);
        calendar.setCurrentDay(day);
        calendar.setCurrentPhase("MORNING");
        return calendar;
    }
}
