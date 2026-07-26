package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import com.footballmanagergamesimulator.user.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MultiplayerRoomControllerOwnershipTest {
    @Test
    void stateExposesOnlyCurrentManagersOwnLiveMatchKey() {
        MultiplayerRoomService service = mock(MultiplayerRoomService.class);
        RoomContinueCoordinator coordinator = mock(RoomContinueCoordinator.class);
        GameCalendarRepository calendars = mock(GameCalendarRepository.class);
        RoomRapidFastForwardService rapid = mock(RoomRapidFastForwardService.class);
        LiveMatchSimulationService live = mock(LiveMatchSimulationService.class);
        GameRoom room = new GameRoom(); room.setId(1L); room.setStatus(RoomStatus.ACTIVE); room.setHostUserId(1);
        User user = new User(); user.setId(2); user.setTeamId(22L);
        GameRoomMember own = member(2, 22); GameRoomMember other = member(1, 11);
        LiveMatchSession ownSession = mockSession(22, 33, 100);
        LiveMatchSession otherSession = mockSession(11, 44, 200);
        when(service.requireMemberRoom()).thenReturn(room); when(service.user()).thenReturn(user); when(service.members(room)).thenReturn(List.of(other, own));
        when(service.currentCycle(room)).thenReturn(null); when(service.votes()).thenReturn(mock(RoomContinueVoteRepository.class));
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.empty());
        when(rapid.state(eq(1L), isNull(), isNull())).thenReturn(new RoomRapidFastForwardService.Progress(RoomRapidFastForwardService.Status.IDLE, 0, 0, 0, 0, false));
        when(live.findAnyUncommittedSessionForTeam(22)).thenReturn(ownSession); when(live.findAnyUncommittedSessionForTeam(11)).thenReturn(otherSession);
        MultiplayerRoomController controller = new MultiplayerRoomController(service, coordinator, calendars, rapid, live);

        Map<String, Object> state = controller.stateEndpoint();
        assertEquals(LiveMatchSimulationService.buildKey(100, 1, 7, 22, 33), state.get("liveMatchKey"));
        verify(live).findAnyUncommittedSessionForTeam(22);
    }

    private static GameRoomMember member(int userId, long teamId) { GameRoomMember member = new GameRoomMember(); member.setUserId(userId); member.setTeamId(teamId); return member; }
    private static LiveMatchSession mockSession(long team1, long team2, long competition) {
        LiveMatchSession session = mock(LiveMatchSession.class); when(session.getTeamId1()).thenReturn(team1); when(session.getTeamId2()).thenReturn(team2); when(session.getCompetitionId()).thenReturn(competition); when(session.getSeason()).thenReturn(1); when(session.getRound()).thenReturn(7); when(session.isFinished()).thenReturn(false); return session;
    }
}
