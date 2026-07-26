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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiplayerRoomServiceRulesTest {
    @Test void chairmanCannotCreateRoom() {
        User chairman = user(1, CareerRole.CHAIRMAN, 10L);
        CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(chairman);
        MultiplayerRoomService service = service(current, chairman, null, null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(new MultiplayerRoomService.CreateRoom("secret", 50, 300, 60, 2)));
    }

    @Test void fastForwardTargetIsCanonicalSeasonAndDayPlusSeasons() {
        User manager = user(1, CareerRole.MANAGER, 10L); CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(manager);
        GameRoom room = new GameRoom(); room.setId(7L); room.setStatus(RoomStatus.ACTIVE); GameRoomMember member = new GameRoomMember(); member.setRoomId(7L); member.setUserId(1); member.setTeamId(10L);
        GameRoomRepository roomRepo = mock(GameRoomRepository.class); GameRoomMemberRepository memberRepo = mock(GameRoomMemberRepository.class); RoomContinueCycleRepository cycleRepo = mock(RoomContinueCycleRepository.class); RoomContinueVoteRepository voteRepo = mock(RoomContinueVoteRepository.class); GameCalendarRepository calendarRepo = mock(GameCalendarRepository.class);
        when(memberRepo.findFirstByUserIdAndMembershipStatus(1, MembershipStatus.ACTIVE)).thenReturn(Optional.of(member)); when(roomRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(room)); when(memberRepo.findActiveForUpdate(7L, 1)).thenReturn(Optional.of(member)); RoomContinueCycle cycle = new RoomContinueCycle(); cycle.setId(11L); cycle.setStatus(CycleStatus.OPEN); when(cycleRepo.findCurrentForUpdate(7L)).thenReturn(Optional.of(cycle)); when(voteRepo.findForUpdate(11L, 1)).thenReturn(Optional.empty()); GameCalendar cal = new GameCalendar(); cal.setSeason(2); cal.setCurrentDay(10); when(calendarRepo.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(cal));
        MultiplayerRoomService service = new MultiplayerRoomService(roomRepo, memberRepo, cycleRepo, voteRepo, current, mock(PasswordEncoder.class), mock(TeamRepository.class), calendarRepo);
        service.setFastForward(true, 1);
        assertEquals(3, member.getFastForwardTargetSeason());
        assertEquals(10, member.getFastForwardTargetDay());
    }

    @Test void lobbyMemberCanLeaveAndRejoinTheSameMembershipRow() {
        User manager = user(2, CareerRole.MANAGER, 22L); CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(manager);
        GameRoom room = new GameRoom(); room.setId(9L); room.setStatus(RoomStatus.LOBBY); room.setHostUserId(1); room.setPasswordHash("encoded");
        GameRoomMember member = new GameRoomMember(); member.setRoomId(9L); member.setUserId(2); member.setTeamId(22L); member.setMembershipStatus(MembershipStatus.ACTIVE);
        GameRoomRepository roomRepo = mock(GameRoomRepository.class); GameRoomMemberRepository memberRepo = mock(GameRoomMemberRepository.class); PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(memberRepo.findFirstByUserIdAndMembershipStatus(2, MembershipStatus.ACTIVE)).thenReturn(Optional.of(member), Optional.empty()); when(roomRepo.findByIdForUpdate(9L)).thenReturn(Optional.of(room)); when(memberRepo.findActiveForUpdate(9L, 2)).thenReturn(Optional.of(member)); when(memberRepo.findAllByRoomIdAndMembershipStatus(9L, MembershipStatus.ACTIVE)).thenReturn(List.of(member));
        when(roomRepo.findOpenForUpdate(List.of(RoomStatus.LOBBY, RoomStatus.ACTIVE))).thenReturn(Optional.of(room)); when(memberRepo.findActiveForUpdate(9L)).thenReturn(List.of()); when(memberRepo.findByRoomIdAndUserId(9L, 2)).thenReturn(Optional.of(member)); when(encoder.matches(anyString(), anyString())).thenReturn(true);
        MultiplayerRoomService service = new MultiplayerRoomService(roomRepo, memberRepo, mock(RoomContinueCycleRepository.class), mock(RoomContinueVoteRepository.class), current, encoder, mock(TeamRepository.class), mock(GameCalendarRepository.class));
        service.leave(); assertEquals(MembershipStatus.LEFT, member.getMembershipStatus());
        service.join("secret"); assertEquals(MembershipStatus.ACTIVE, member.getMembershipStatus());
    }

    @Test void activeRoomRejectsLeaveAndHostLobbyLeaveDeletesRoomForRecreate() {
        User manager = user(1, CareerRole.MANAGER, 11L); CurrentUserService current = mock(CurrentUserService.class); when(current.requireUser()).thenReturn(manager);
        GameRoom room = new GameRoom(); room.setId(10L); room.setStatus(RoomStatus.ACTIVE); room.setHostUserId(1);
        GameRoomMember member = new GameRoomMember(); member.setRoomId(10L); member.setUserId(1); member.setTeamId(11L);
        GameRoomRepository roomRepo = mock(GameRoomRepository.class); GameRoomMemberRepository memberRepo = mock(GameRoomMemberRepository.class);
        when(memberRepo.findFirstByUserIdAndMembershipStatus(1, MembershipStatus.ACTIVE)).thenReturn(Optional.of(member)); when(roomRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(room)); when(memberRepo.findActiveForUpdate(10L, 1)).thenReturn(Optional.of(member));
        MultiplayerRoomService service = new MultiplayerRoomService(roomRepo, memberRepo, mock(RoomContinueCycleRepository.class), mock(RoomContinueVoteRepository.class), current, mock(PasswordEncoder.class), mock(TeamRepository.class), mock(GameCalendarRepository.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, service::leave);
        room.setStatus(RoomStatus.LOBBY); when(memberRepo.findAllByRoomIdAndMembershipStatus(10L, MembershipStatus.ACTIVE)).thenReturn(List.of()); when(memberRepo.findAllByRoomIdAndMembershipStatus(10L, MembershipStatus.LEFT)).thenReturn(List.of());
        service.leave(); verify(roomRepo).delete(room);
    }

    private MultiplayerRoomService service(CurrentUserService current, User user, GameRoomRepository roomRepo, GameRoomMemberRepository memberRepo) {
        return new MultiplayerRoomService(roomRepo == null ? mock(GameRoomRepository.class) : roomRepo, memberRepo == null ? mock(GameRoomMemberRepository.class) : memberRepo, mock(RoomContinueCycleRepository.class), mock(RoomContinueVoteRepository.class), current, mock(PasswordEncoder.class), mock(TeamRepository.class), mock(GameCalendarRepository.class));
    }
    private User user(int id, CareerRole role, Long teamId) { User u = new User(); u.setId(id); u.setCareerRole(role); u.setTeamId(teamId); return u; }
}
