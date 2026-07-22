package com.footballmanagergamesimulator.user;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserContext {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CurrentUserService currentUserService;

    /**
     * Get the teamId for the authenticated server-side session principal.
     * Throws RuntimeException if user not found or has no team.
     */
    public long getTeamId(HttpServletRequest request) {
        return currentUserService.requireTeamId(request);
    }

    /**
     * Get teamId or return null when no authenticated manager-team context exists.
     */
    public Long getTeamIdOrNull(HttpServletRequest request) {
        try {
            return getTeamId(request);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Get ALL human-managed team IDs (for simulation/season logic).
     */
    public List<Long> getAllHumanTeamIds() {
        return userRepository.findAll().stream()
                .filter(u -> u.getTeamId() != null && u.getTeamId() > 0)
                .map(User::getTeamId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Check if a given teamId is managed by any human user.
     */
    public boolean isHumanTeam(long teamId) {
        return userRepository.findAll().stream()
                .anyMatch(u -> u.getTeamId() != null && u.getTeamId() == teamId);
    }

    /**
     * Check if the authenticated session user is fired.
     */
    public boolean isCurrentUserFired(HttpServletRequest request) {
        User user = currentUserService.getUserOrNull(request);
        return user != null && user.isFired();
    }

    /**
     * Check if ANY human user is currently fired (game should pause).
     */
    public boolean isAnyUserFired() {
        return userRepository.findAll().stream().anyMatch(User::isFired);
    }

    /**
     * Get the User entity for the current request, or null.
     */
    public User getUserOrNull(HttpServletRequest request) {
        return currentUserService.getUserOrNull(request);
    }
}
