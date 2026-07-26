package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomContinueCoordinatorTest {
    private MultiplayerRoomService rooms;
    private RoomContinueVoteRepository votes;
    private RoomContinueCycleRepository cycles;
    private GameCalendarRepository calendars;
    private RoomContinueCoordinator coordinator;
    private GameRoom room;
    private RoomContinueCycle cycle;

    @BeforeEach void setUp() {
        rooms = mock(MultiplayerRoomService.class); votes = mock(RoomContinueVoteRepository.class); cycles = mock(RoomContinueCycleRepository.class); calendars = mock(GameCalendarRepository.class);
        when(rooms.votes()).thenReturn(votes); when(rooms.cycles()).thenReturn(cycles); when(rooms.membersRepository()).thenReturn(mock(GameRoomMemberRepository.class));
        room = new GameRoom(); room.setId(7L); room.setStatus(RoomStatus.ACTIVE); room.setContinueThresholdPercent(50); room.setMajorityTimeoutSeconds(60); room.setDayTimeoutSeconds(300);
        cycle = new RoomContinueCycle(); cycle.setId(11L); cycle.setRoomId(7L); cycle.setSeason(1); cycle.setGameDay(10); cycle.setStatus(CycleStatus.OPEN); cycle.setDayDeadline(Instant.now().plusSeconds(300));
        GameCalendar calendar = calendar(1, 10); when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        coordinator = new RoomContinueCoordinator(rooms, mock(RoomAdvanceService.class), calendars, mock(RoomContinueLifecycleService.class));
    }

    @Test void zeroOfTwoWaitsForDayTimeout() {
        configureMembers(2); when(votes.countByCycleId(11L)).thenReturn(0L); cycle.setDayDeadline(Instant.now().minusSeconds(1));
        AdvanceClaim claim = coordinator.claimExpired();
        assertNotNull(claim); assertEquals(CycleStatus.ADVANCING, cycle.getStatus());
    }

    @Test void oneOfTwoStartsMajorityTimeoutThenClaimsOnItsDeadline() {
        configureMembers(2); when(votes.countByCycleId(11L)).thenReturn(1L);
        assertNull(coordinator.claimExpired()); assertNotNull(cycle.getMajorityDeadline());
        Instant firstDeadline = cycle.getMajorityDeadline(); Instant lockedDeadline = Instant.now().minusSeconds(1); cycle.setMajorityDeadline(lockedDeadline);
        AdvanceClaim claim = coordinator.claimExpired();
        assertNotNull(claim); assertNotEquals(firstDeadline, cycle.getMajorityDeadline()); assertEquals(lockedDeadline, cycle.getMajorityDeadline()); assertEquals("NORMAL", cycle.getAdvanceMode());
    }

    @Test void twoOfTwoClaimsImmediately() {
        configureMembers(2); when(votes.countByCycleId(11L)).thenReturn(2L);
        assertNotNull(coordinator.claimExpired()); assertNull(cycle.getMajorityDeadline());
    }

    @Test void oneOfFourWaitsAndTwoOfFourStartsMajority() {
        configureMembers(4); when(votes.countByCycleId(11L)).thenReturn(1L); assertNull(coordinator.claimExpired());
        cycle.setStatus(CycleStatus.OPEN); cycle.setMajorityDeadline(null); when(votes.countByCycleId(11L)).thenReturn(2L); assertNull(coordinator.claimExpired()); assertNotNull(cycle.getMajorityDeadline());
    }

    @Test void existingVoteIsIdempotentOnDoubleClick() {
        configureMembers(2); when(rooms.lockMemberRoom(1)).thenReturn(room); when(rooms.memberForUpdate(room, 1)).thenReturn(member(1)); when(rooms.currentCycleForUpdate(room)).thenReturn(cycle); when(votes.findForUpdate(11L, 1)).thenReturn(Optional.of(vote(11L, 1, VoteSource.MANUAL))); when(votes.countByCycleId(11L)).thenReturn(1L);
        assertNull(coordinator.cast(1, VoteSource.MANUAL)); verify(votes, never()).saveAndFlush(any());
    }

    @Test void allFastForwardIsLeftToContinuousWorkerAndDoesNotUseHumanAlwaysContinue() {
        configureMembers(2); List<GameRoomMember> members = rooms.membersRepository().findActiveForUpdate(7L);
        members.forEach(m -> { m.setFastForwardEnabled(true); m.setFastForwardUntilAbsoluteDay(500L); }); when(votes.countByCycleId(11L)).thenReturn(2L);
        AdvanceClaim claim = coordinator.claimExpired();
        assertNull(claim); assertEquals(CycleStatus.OPEN, cycle.getStatus());
    }

    private void configureMembers(int count) { List<GameRoomMember> list = java.util.stream.IntStream.range(0, count).mapToObj(i -> member(i + 1)).toList(); when(rooms.openRoomForUpdate()).thenReturn(room); when(rooms.currentCycleForUpdate(room)).thenReturn(cycle); when(rooms.membersRepository().findActiveForUpdate(7L)).thenReturn(list); when(rooms.members(room)).thenReturn(list); }
    private GameRoomMember member(int userId) { GameRoomMember m = new GameRoomMember(); m.setUserId(userId); m.setRoomId(7L); m.setTeamId(userId); return m; }
    private RoomContinueVote vote(long cycleId, int userId, VoteSource source) { RoomContinueVote v = new RoomContinueVote(); v.setCycleId(cycleId); v.setUserId(userId); v.setSource(source); return v; }
    private GameCalendar calendar(int season, int day) { GameCalendar c = new GameCalendar(); c.setSeason(season); c.setCurrentDay(day); c.setCurrentPhase("MORNING"); return c; }
}
