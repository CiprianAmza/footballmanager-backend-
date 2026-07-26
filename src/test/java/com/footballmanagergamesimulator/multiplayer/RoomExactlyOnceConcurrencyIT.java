package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.service.GameAdvanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN_BY_POLICY: opt-in Spring/H2 concurrency coverage for owner-run validation. */
@SpringBootTest
@EnabledIfSystemProperty(named = "run.multiplayer.it", matches = "true")
class RoomExactlyOnceConcurrencyIT {
    @Autowired private GameCalendarRepository calendars;
    @Autowired private GameRoomRepository rooms;
    @Autowired private GameRoomMemberRepository members;
    @Autowired private RoomContinueCycleRepository cycles;
    @Autowired private RoomContinueCoordinator coordinator;
    @SpyBean private RoomAdvanceService roomAdvance;
    @MockBean private GameAdvanceService gameAdvance;

    @BeforeEach
    void seed() {
        cycles.deleteAll(); members.deleteAll(); rooms.deleteAll(); calendars.deleteAll();
        GameCalendar calendar = new GameCalendar(); calendar.setSeason(1); calendar.setCurrentDay(365); calendar.setCurrentPhase("EVENING"); calendars.saveAndFlush(calendar);
        GameRoom room = new GameRoom(); room.setHostUserId(7001); room.setPasswordHash("test-only"); room.setStatus(RoomStatus.ACTIVE); rooms.saveAndFlush(room);
        GameRoomMember member = new GameRoomMember(); member.setRoomId(room.getId()); member.setUserId(7001); member.setTeamId(7001); members.saveAndFlush(member);
        RoomContinueCycle cycle = new RoomContinueCycle(); cycle.setRoomId(room.getId()); cycle.setSeason(1); cycle.setGameDay(365); cycle.setStatus(CycleStatus.ADVANCING);
        cycle.setOpenedAt(Instant.now().minusSeconds(60)); cycle.setDayDeadline(Instant.now().minusSeconds(60)); cycle.setAdvanceToken("long-day-token"); cycle.setAdvanceLeaseUntil(Instant.now().minusSeconds(1));
        cycles.saveAndFlush(cycle);
    }

    @Test
    void twoRecoveryAttemptsShareOneRealCoordinatorLifecycleAdvance() throws Exception {
        AtomicBoolean calendarCommitted = new AtomicBoolean();
        doAnswer(invocation -> {
            Thread.sleep(31_000); // deliberately longer than the persisted lease
            synchronized (calendarCommitted) {
                GameCalendar current = calendars.findTopByOrderBySeasonDesc().orElseThrow();
                if (RoomDate.of(current).equals(new RoomDate(1, 365)) && calendarCommitted.compareAndSet(false, true)) {
                    current.setSeason(2); current.setCurrentDay(1); current.setCurrentPhase("MORNING"); calendars.saveAndFlush(current);
                }
            }
            return Map.of("roomAdvanceStatus", "ADVANCED", "season", 2, "day", 1);
        }).when(gameAdvance).advanceOneDayUnattended(eq(1), eq(365), anySet(), anyBoolean());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AdvanceClaim first = coordinator.recoverExpired();
            AdvanceClaim second = coordinator.recoverExpired();
            assertNotNull(first); assertNotNull(second);
            Future<?> a = executor.submit(() -> coordinator.advanceClaimed(first));
            Future<?> b = executor.submit(() -> coordinator.advanceClaimed(second));
            a.get(45, TimeUnit.SECONDS); b.get(45, TimeUnit.SECONDS);

            verify(roomAdvance, timeout(1_000).times(1)).advanceOneDay(eq(1), eq(365), anySet(), anyBoolean());
            GameCalendar actual = calendars.findTopByOrderBySeasonDesc().orElseThrow();
            assertEquals(new RoomDate(2, 1), RoomDate.of(actual));
            assertEquals(1, cycles.findAllByRoomId(rooms.findFirstByStatusIn(List.of(RoomStatus.ACTIVE)).orElseThrow().getId()).stream().filter(c -> c.getStatus() == CycleStatus.COMPLETED).count());
            assertEquals(1, cycles.findAllByRoomId(actualRoomId()).stream().filter(c -> c.getStatus() == CycleStatus.OPEN).count());
        } finally { executor.shutdownNow(); }
    }

    private Long actualRoomId() { return rooms.findFirstByStatusIn(List.of(RoomStatus.ACTIVE)).orElseThrow().getId(); }
}
