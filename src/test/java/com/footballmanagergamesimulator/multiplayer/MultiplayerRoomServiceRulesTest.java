package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiplayerRoomServiceRulesTest {
    @Test void chairmanCannotCreateRoom() {
        User chairman = user(1, CareerRole.CHAIRMAN, 10L);
        CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(chairman);
        MultiplayerRoomService service = service(current, chairman, null, null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(new MultiplayerRoomService.CreateRoom("secret", 50, 300, 60, 2)));
    }

    @Test void fastForwardTargetIsCurrentAbsoluteDayPlusSeasons() {
        User manager = user(1, CareerRole.MANAGER, 10L); CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(manager);
        GameRoom room = new GameRoom(); room.setId(7L); room.setStatus(RoomStatus.ACTIVE); GameRoomMember member = new GameRoomMember(); member.setRoomId(7L); member.setUserId(1); member.setTeamId(10L);
        GameRoomRepository roomRepo = mock(GameRoomRepository.class); GameRoomMemberRepository memberRepo = mock(GameRoomMemberRepository.class); RoomContinueCycleRepository cycleRepo = mock(RoomContinueCycleRepository.class); RoomContinueVoteRepository voteRepo = mock(RoomContinueVoteRepository.class); GameCalendarRepository calendarRepo = mock(GameCalendarRepository.class);
        when(memberRepo.findFirstByUserIdAndMembershipStatus(1, MembershipStatus.ACTIVE)).thenReturn(Optional.of(member)); when(roomRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(room)); when(memberRepo.findActiveForUpdate(7L, 1)).thenReturn(Optional.of(member)); RoomContinueCycle cycle = new RoomContinueCycle(); cycle.setId(11L); cycle.setStatus(CycleStatus.OPEN); when(cycleRepo.findOpenForUpdate(7L)).thenReturn(Optional.of(cycle)); when(voteRepo.findForUpdate(11L, 1)).thenReturn(Optional.empty()); GameCalendar cal = new GameCalendar(); cal.setSeason(2); cal.setCurrentDay(10); when(calendarRepo.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(cal));
        MultiplayerRoomService service = new MultiplayerRoomService(roomRepo, memberRepo, cycleRepo, voteRepo, current, mock(PasswordEncoder.class), mock(TeamRepository.class), calendarRepo);
        service.setFastForward(true, 1);
        assertEquals(2L * 366L + 10L, member.getFastForwardUntilAbsoluteDay());
    }

    private MultiplayerRoomService service(CurrentUserService current, User user, GameRoomRepository roomRepo, GameRoomMemberRepository memberRepo) {
        return new MultiplayerRoomService(roomRepo == null ? mock(GameRoomRepository.class) : roomRepo, memberRepo == null ? mock(GameRoomMemberRepository.class) : memberRepo, mock(RoomContinueCycleRepository.class), mock(RoomContinueVoteRepository.class), current, mock(PasswordEncoder.class), mock(TeamRepository.class), mock(GameCalendarRepository.class));
    }
    private User user(int id, CareerRole role, Long teamId) { User u = new User(); u.setId(id); u.setCareerRole(role); u.setTeamId(teamId); return u; }
}
