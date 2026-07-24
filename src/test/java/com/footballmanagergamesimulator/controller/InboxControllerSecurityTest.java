package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.TeamAccessGuard;
import com.footballmanagergamesimulator.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InboxControllerSecurityTest {
    @Test
    void managerListUsesOnlyManagerAndBothAudience() {
        ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        TeamAccessGuard guard = mock(TeamAccessGuard.class);
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        User user = user(3, CareerRole.MANAGER, 9L);
        when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user);
        when(guard.resolveInboxTeamId(any(), eq(0L))).thenReturn(9L);
        InboxController controller = controller(messages, guard, current, profiles);

        controller.me(mock(HttpServletRequest.class));
        controller.meUnreadCount(mock(HttpServletRequest.class));

        verify(messages).findAllByTeamIdAndAudienceInOrderByIdDesc(9L,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
        verify(messages).countByTeamIdAndAudienceInAndIsReadFalse(9L,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
        verify(messages, never()).findAllByTeamIdOrderByIdDesc(9L);
    }

    @Test
    void chairmanCannotReadAnotherProfileMessage() {
        ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        TeamAccessGuard guard = mock(TeamAccessGuard.class);
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        User user = user(3, CareerRole.CHAIRMAN, null);
        PersonProfile profile = profile(11L, 3);
        when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user);
        when(profiles.findByUserId(3)).thenReturn(Optional.of(profile));
        ManagerInbox message = message(42L, 12L, InboxAudience.CHAIRMAN);
        when(messages.findById(42L)).thenReturn(Optional.of(message));
        InboxController controller = controller(messages, guard, current, profiles);

        assertThat(controller.meRead(42L, mock(HttpServletRequest.class)).getStatusCode().value()).isEqualTo(403);
        verify(messages, never()).save(any());
    }

    @Test
    void managerReadMatrixAllowsOnlyManagerAndBoth() {
        for (InboxAudience audience : InboxAudience.values()) {
            ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
            CurrentUserService current = mock(CurrentUserService.class);
            TeamAccessGuard guard = mock(TeamAccessGuard.class);
            PersonProfileRepository profiles = mock(PersonProfileRepository.class);
            when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user(3, CareerRole.MANAGER, 9L));
            when(messages.findById(42L)).thenReturn(Optional.of(message(42L, 0L, audience)));
            when(guard.canAccessInboxMessage(any(), any())).thenReturn(true);
            InboxController controller = controller(messages, guard, current, profiles);

            int status = controller.meRead(42L, mock(HttpServletRequest.class)).getStatusCode().value();
            if (audience == InboxAudience.MANAGER || audience == InboxAudience.BOTH) {
                assertThat(status).isEqualTo(200);
                verify(messages).save(any());
            } else {
                assertThat(status).isEqualTo(403);
                verify(messages, never()).save(any());
            }
        }
    }

    @Test
    void managerMarkAllReadUsesTheSameAudienceMatrix() {
        ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        TeamAccessGuard guard = mock(TeamAccessGuard.class);
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user(3, CareerRole.MANAGER, 9L));
        when(guard.resolveInboxTeamId(any(), eq(0L))).thenReturn(9L);
        when(messages.findAllByTeamIdAndAudienceInAndIsReadFalse(eq(9L), any())).thenReturn(List.of());

        InboxController controller = controller(messages, guard, current, profiles);
        controller.meMarkAllRead(mock(HttpServletRequest.class));

        verify(messages).findAllByTeamIdAndAudienceInAndIsReadFalse(9L,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
        verify(messages, never()).findAllByTeamIdAndIsReadFalse(anyLong());
    }

    @Test
    void chairmanReadMatrixAllowsOnlyChairmanAndBothForOwnProfile() {
        for (InboxAudience audience : InboxAudience.values()) {
            ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
            CurrentUserService current = mock(CurrentUserService.class);
            TeamAccessGuard guard = mock(TeamAccessGuard.class);
            PersonProfileRepository profiles = mock(PersonProfileRepository.class);
            when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user(3, CareerRole.CHAIRMAN, null));
            when(profiles.findByUserId(3)).thenReturn(Optional.of(profile(11L, 3)));
            when(messages.findById(42L)).thenReturn(Optional.of(message(42L, 11L, audience)));
            InboxController controller = controller(messages, guard, current, profiles);

            int status = controller.meRead(42L, mock(HttpServletRequest.class)).getStatusCode().value();
            if (audience == InboxAudience.CHAIRMAN || audience == InboxAudience.BOTH) {
                assertThat(status).isEqualTo(200);
                verify(messages).save(any());
            } else {
                assertThat(status).isEqualTo(403);
                verify(messages, never()).save(any());
            }
        }
    }

    @Test
    void chairmanListAndCountUseChairmanAndBothForOwnProfile() {
        ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
        CurrentUserService current = mock(CurrentUserService.class);
        TeamAccessGuard guard = mock(TeamAccessGuard.class);
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        when(current.getUserOrNull(any(HttpServletRequest.class))).thenReturn(user(3, CareerRole.CHAIRMAN, null));
        when(profiles.findByUserId(3)).thenReturn(Optional.of(profile(11L, 3)));
        InboxController controller = controller(messages, guard, current, profiles);

        controller.me(mock(HttpServletRequest.class));
        controller.meUnreadCount(mock(HttpServletRequest.class));
        controller.meMarkAllRead(mock(HttpServletRequest.class));

        List<InboxAudience> expected = List.of(InboxAudience.CHAIRMAN, InboxAudience.BOTH);
        verify(messages).findAllByRecipientProfileIdAndAudienceInOrderByIdDesc(11L, expected);
        verify(messages).countByRecipientProfileIdAndAudienceInAndIsReadFalse(11L, expected);
    }

    private static InboxController controller(ManagerInboxRepository messages, TeamAccessGuard guard,
                                               CurrentUserService current, PersonProfileRepository profiles) {
        InboxController controller = new InboxController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "managerInboxRepository", messages);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "teamAccessGuard", guard);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "currentUserService", current);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "profileRepository", profiles);
        return controller;
    }

    private static User user(int id, CareerRole role, Long teamId) {
        User value = new User(); value.setId(id); value.setCareerRole(role); value.setTeamId(teamId); return value;
    }

    private static PersonProfile profile(long id, int userId) {
        PersonProfile value = new PersonProfile(); value.setId(id); value.setUserId(userId); return value;
    }

    private static ManagerInbox message(long id, long recipient, InboxAudience audience) {
        ManagerInbox value = new ManagerInbox(); value.setId(id); value.setRecipientProfileId(recipient); value.setAudience(audience); return value;
    }
}
