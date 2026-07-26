package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MultiplayerRoomLegacyGateTest {
    @Test
    void activeRoomGateDoesNotDependOnCurrentUserMembership() {
        GameRoomRepository rooms = mock(GameRoomRepository.class);
        when(rooms.findFirstByStatusIn(java.util.List.of(RoomStatus.ACTIVE)))
                .thenReturn(Optional.of(new GameRoom()));
        MultiplayerRoomService service = new MultiplayerRoomService(rooms,
                mock(GameRoomMemberRepository.class), mock(RoomContinueCycleRepository.class),
                mock(RoomContinueVoteRepository.class), mock(com.footballmanagergamesimulator.user.CurrentUserService.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(com.footballmanagergamesimulator.repository.TeamRepository.class),
                mock(com.footballmanagergamesimulator.repository.GameCalendarRepository.class));
        assertTrue(service.hasActiveRoom());
    }
}
