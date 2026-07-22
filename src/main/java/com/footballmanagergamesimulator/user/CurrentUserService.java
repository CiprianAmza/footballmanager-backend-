package com.footballmanagergamesimulator.user;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    /** Retained only so legacy callers/tests compile. It is never trusted. */
    @Deprecated
    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Integer getUserIdOrNull(HttpServletRequest ignoredRequest) {
        User user = getUserOrNull();
        return user == null ? null : user.getId();
    }

    public User getUserOrNull(HttpServletRequest ignoredRequest) {
        return getUserOrNull();
    }

    public User getUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(authentication.getName()).orElse(null);
    }

    public User requireUser(HttpServletRequest ignoredRequest) {
        return requireUser();
    }

    public User requireUser() {
        User user = getUserOrNull();
        if (user == null) throw new IllegalStateException("Authenticated user not found");
        return user;
    }

    public long requireTeamId(HttpServletRequest ignoredRequest) {
        User user = requireUser();
        if (user.getTeamId() == null || user.getTeamId() <= 0) {
            throw new IllegalStateException("User has no team assigned");
        }
        return user.getTeamId();
    }
}
