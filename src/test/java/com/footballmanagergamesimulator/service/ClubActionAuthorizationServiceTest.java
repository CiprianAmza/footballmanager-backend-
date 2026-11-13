package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.economy.ClubCapTableService;
import com.footballmanagergamesimulator.economy.PersonalAccountRepository;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClubActionAuthorizationServiceTest {

    private final CurrentUserService users = mock(CurrentUserService.class);
    private final CoachPermissionService permissions = mock(CoachPermissionService.class);
    private final ClubActionAuthorizationService authorization = new ClubActionAuthorizationService(
            users, mock(PersonProfileService.class), mock(PersonalAccountRepository.class),
            mock(ClubCapTableService.class), permissions);

    @Test
    @DisplayName("""
            GIVEN a manager whose chairman took away both transfers and contracts
            WHEN he holds a first-day meeting (a manager duty), or negotiates a contract
            THEN the meeting is allowed for his own club, the contract talks are not
            """)
    void chairmanSwitchesDoNotBarTheManagersOwnDuties() {
        User manager = new User();
        manager.setCareerRole(CareerRole.MANAGER);
        when(users.requireUser(any())).thenReturn(manager);
        when(users.requireTeamId(any())).thenReturn(7L);
        when(permissions.canBuyPlayers(7L)).thenReturn(false);
        when(permissions.canNegotiateContracts(7L)).thenReturn(false);

        assertEquals(7L, authorization.authorize(null, Map.of(), Action.MANAGER_DUTY).teamId());
        ResponseStatusException contract = assertThrows(ResponseStatusException.class,
                () -> authorization.authorize(null, Map.of(), Action.CONTRACT));
        assertEquals("CHAIRMAN_CONTRACT_CONTROLLED", contract.getReason());
        ResponseStatusException otherClub = assertThrows(ResponseStatusException.class,
                () -> authorization.authorize(null, Map.of("teamId", 8), Action.MANAGER_DUTY));
        assertEquals("MANAGER_TEAM_MISMATCH", otherClub.getReason());
    }

    @Test
    @DisplayName("""
            GIVEN a chairman who controls a club
            WHEN he tries a manager duty for it (refuse the manager's press conference, answer the board for him)
            THEN he is refused
            """)
    void chairmanCannotDoTheManagersDuties() {
        User chairman = new User();
        chairman.setCareerRole(CareerRole.CHAIRMAN);
        when(users.requireUser(any())).thenReturn(chairman);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authorization.authorize(null, Map.of("teamId", 7), Action.MANAGER_DUTY));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("MANAGER_ONLY", refused.getReason());
    }
}
