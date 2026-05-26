package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.ManagerInbox;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class TeamAccessGuardTest {

    private final TeamAccessGuard teamAccessGuard = new TeamAccessGuard();

    @Test
    void canAccessTeamChecksOnlyCurrentAssignedTeam() {
        User user = new User();
        user.setTeamId(10L);
        user.setLastTeamId(8L);

        assertTrue(teamAccessGuard.canAccessTeam(user, 10L));
        assertFalse(teamAccessGuard.canAccessTeam(user, 8L));
        assertFalse(teamAccessGuard.canAccessTeam(user, 99L));
    }

    @Test
    void resolveInboxTeamIdUsesCurrentTeamForActiveUser() {
        User user = new User();
        user.setTeamId(10L);
        user.setLastTeamId(8L);

        assertEquals(10L, teamAccessGuard.resolveInboxTeamId(user, 0));
        assertEquals(10L, teamAccessGuard.resolveInboxTeamId(user, 10L));
        assertNull(teamAccessGuard.resolveInboxTeamId(user, 8L));
    }

    @Test
    void resolveInboxTeamIdFallsBackToLastTeamForJoblessUser() {
        User user = new User();
        user.setTeamId(null);
        user.setLastTeamId(8L);

        assertEquals(8L, teamAccessGuard.resolveInboxTeamId(user, 0));
        assertEquals(8L, teamAccessGuard.resolveInboxTeamId(user, 8L));
        assertNull(teamAccessGuard.resolveInboxTeamId(user, 10L));
    }

    @Test
    void resolveInboxTeamIdReturnsNullWhenUserHasNoAccessibleTeam() {
        User user = new User();
        user.setTeamId(null);
        user.setLastTeamId(null);

        assertNull(teamAccessGuard.resolveInboxTeamId(user, 0));
        assertNull(teamAccessGuard.resolveInboxTeamId(user, 7L));
    }

    @Test
    void canAccessInboxMessageReturnsFalseForNullMessage() {
        HttpServletRequest request = new MockHttpServletRequest();

        assertFalse(teamAccessGuard.canAccessInboxMessage(request, null));
    }

    @Test
    void canAccessInboxMessageAllowsOwnTeam() {
        User user = new User();
        user.setTeamId(10L);

        ManagerInbox msg = new ManagerInbox();
        msg.setTeamId(10L);

        assertTrue(teamAccessGuard.canAccessInboxMessage(user, msg));
    }

    @Test
    void canAccessInboxMessageDeniesOtherTeam() {
        User user = new User();
        user.setTeamId(10L);

        ManagerInbox msg = new ManagerInbox();
        msg.setTeamId(99L);

        assertFalse(teamAccessGuard.canAccessInboxMessage(user, msg));
    }

    @Test
    void canAccessInboxMessageAllowsLastTeamForJoblessUser() {
        User user = new User();
        user.setTeamId(null);
        user.setLastTeamId(8L);

        ManagerInbox msg = new ManagerInbox();
        msg.setTeamId(8L);

        assertTrue(teamAccessGuard.canAccessInboxMessage(user, msg));
    }

    @Test
    void canAccessInboxMessageDeniesForNullUser() {
        ManagerInbox msg = new ManagerInbox();
        msg.setTeamId(10L);

        assertFalse(teamAccessGuard.canAccessInboxMessage((User) null, msg));
    }
}
