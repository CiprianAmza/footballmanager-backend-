package com.footballmanagergamesimulator.user;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {

    @InjectMocks
    private CurrentUserService currentUserService;

    @Mock
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getUserOrNullReturnsNullWhenHeaderMissing() {
        HttpServletRequest request = new MockHttpServletRequest();

        assertNull(currentUserService.getUserOrNull(request));
    }

    @Test
    void requireUserThrowsWhenHeaderInvalid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "abc");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> currentUserService.requireUser(request));
        assertEquals("Invalid X-User-Id header", ex.getMessage());
    }

    @Test
    void requireTeamIdReturnsAssignedTeam() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "7");

        User user = new User();
        user.setId(7);
        user.setTeamId(42L);

        when(userRepository.findById(7)).thenReturn(Optional.of(user));

        assertEquals(42L, currentUserService.requireTeamId(request));
    }

    @Test
    void requireUserThrowsWhenUserMissingInDb() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "99");
        when(userRepository.findById(99)).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> currentUserService.requireUser(request));
        assertEquals("User not found: 99", ex.getMessage());
    }

    @Test
    void requireTeamIdThrowsWhenUserHasNoTeam() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "7");

        User user = new User();
        user.setId(7);
        user.setTeamId(null);
        when(userRepository.findById(7)).thenReturn(Optional.of(user));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> currentUserService.requireTeamId(request));
        assertEquals("User has no team assigned", ex.getMessage());
    }

    @Test
    void requireTeamIdThrowsWhenTeamIdIsZero() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserService.USER_ID_HEADER, "7");

        User user = new User();
        user.setId(7);
        user.setTeamId(0L);
        when(userRepository.findById(7)).thenReturn(Optional.of(user));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> currentUserService.requireTeamId(request));
        assertEquals("User has no team assigned", ex.getMessage());
    }
}
