package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.service.GameLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * NOT_RUN_BY_POLICY: owner-run Spring/H2 concurrency coverage. The opt-in
 * property is deliberately explicit so this class is compiled but not run by
 * the targeted unit-test command.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "run.multiplayer.it", matches = "true")
class RoomExactlyOnceConcurrencyIT {
    @Autowired private GameCalendarRepository calendars;
    @Autowired private GameRoomRepository rooms;
    @Autowired private RoomContinueCycleRepository cycles;
    @Autowired private GameLock gameLock;

    @BeforeEach
    void seed() {
        cycles.deleteAll();
        rooms.deleteAll();
        calendars.deleteAll();

        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(1);
        calendar.setCurrentDay(365);
        calendar.setCurrentPhase("EVENING");
        calendars.saveAndFlush(calendar);

        GameRoom room = new GameRoom();
        room.setHostUserId(7001);
        room.setPasswordHash("test-only");
        room.setStatus(RoomStatus.ACTIVE);
        rooms.saveAndFlush(room);

        RoomContinueCycle cycle = new RoomContinueCycle();
        cycle.setRoomId(room.getId());
        cycle.setSeason(1);
        cycle.setGameDay(365);
        cycle.setStatus(CycleStatus.ADVANCING);
        cycle.setOpenedAt(Instant.now().minusSeconds(60));
        cycle.setDayDeadline(Instant.now().minusSeconds(60));
        cycle.setAdvanceToken("long-day-token");
        cycle.setAdvanceLeaseUntil(Instant.now().minusSeconds(1));
        cycles.saveAndFlush(cycle);
    }

    @Test
    void longDayPastLeaseTwoRecoveriesProduceOneSeasonTransitionAndOneEffectSet() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch firstExecutorHoldingLock = new CountDownLatch(1);
        AtomicInteger effectSets = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> {
                gameLock.lock();
                try {
                    firstExecutorHoldingLock.countDown();
                    // Deliberately hold the critical section longer than the
                    // 30-second claim lease. Heartbeats/fencing must prevent a
                    // second executor from entering while this work is alive.
                    sleep(31_000);
                    GameCalendar current = calendars.findTopByOrderBySeasonDesc().orElseThrow();
                    if (RoomDate.of(current).equalsDate(new RoomDate(1, 365))) {
                        GameCalendar next = new GameCalendar();
                        next.setSeason(2);
                        next.setCurrentDay(1);
                        next.setCurrentPhase("MORNING");
                        calendars.saveAndFlush(next);
                        effectSets.incrementAndGet();
                    }
                } finally {
                    gameLock.unlock();
                }
            });

            firstExecutorHoldingLock.await(5, TimeUnit.SECONDS);
            Future<RoomDate> recovery1 = executor.submit(() -> recoverWithoutSideEffects(new RoomDate(1, 365)));
            Future<RoomDate> recovery2 = executor.submit(() -> recoverWithoutSideEffects(new RoomDate(1, 365)));
            first.get(40, TimeUnit.SECONDS);

            assertEquals(new RoomDate(2, 1), recovery1.get(10, TimeUnit.SECONDS));
            assertEquals(new RoomDate(2, 1), recovery2.get(10, TimeUnit.SECONDS));
            GameCalendar actual = calendars.findTopByOrderBySeasonDesc().orElseThrow();
            assertEquals(2, actual.getSeason());
            assertEquals(1, actual.getCurrentDay());
            assertEquals(1, effectSets.get(), "one executor committed one effect set");
            assertNotNull(cycles.findFirstByRoomIdAndStatusOrderByIdDesc(
                    rooms.findFirstByStatusIn(List.of(RoomStatus.ACTIVE)).orElseThrow().getId(), CycleStatus.ADVANCING));
        } finally {
            executor.shutdownNow();
        }
    }

    private RoomDate recoverWithoutSideEffects(RoomDate expected) {
        gameLock.lock();
        try {
            RoomDate actual = calendars.findTopByOrderBySeasonDesc().map(RoomDate::of).orElseThrow();
            return actual.isAfter(expected) ? actual : expected;
        } finally {
            gameLock.unlock();
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
