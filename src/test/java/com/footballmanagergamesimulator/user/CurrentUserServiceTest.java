package com.footballmanagergamesimulator.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {

    private CurrentUserService currentUserService;
    @Mock private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        currentUserService = new CurrentUserService(userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void headerCannotImpersonateAnotherUser() {
        User alice = user(7, "alice", 42L);
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));
        authenticate("alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "99");

        assertEquals(7, currentUserService.requireUser(request).getId());
        assertEquals(42L, currentUserService.requireTeamId(request));
    }

    @Test
    void missingPrincipalIsNotReplacedByHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "7");

        assertNull(currentUserService.getUserOrNull(request));
        assertThrows(IllegalStateException.class, () -> currentUserService.requireUser(request));
    }

    @Test
    void chairmanWithoutTeamCannotAcquireManagerTeamContext() {
        User chairman = user(8, "chair", null);
        chairman.setCareerRole(CareerRole.CHAIRMAN);
        when(userRepository.findByUsernameIgnoreCase("chair")).thenReturn(Optional.of(chairman));
        authenticate("chair");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> currentUserService.requireTeamId(new MockHttpServletRequest()));
        assertEquals("User has no team assigned", exception.getMessage());
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private User user(int id, String username, Long teamId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setTeamId(teamId);
        user.setActive(true);
        return user;
    }
}
