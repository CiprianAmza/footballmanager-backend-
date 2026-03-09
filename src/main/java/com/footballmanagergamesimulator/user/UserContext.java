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

    /**
     * Get the teamId for the current user from the X-User-Id header.
     * Throws RuntimeException if user not found or has no team.
     */
    public long getTeamId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new RuntimeException("Missing X-User-Id header");
        }
        int userId;
        try {
            userId = Integer.parseInt(userIdHeader);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid X-User-Id header");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new RuntimeException("User not found: " + userId);
        }
        if (user.getTeamId() == null || user.getTeamId() <= 0) {
            throw new RuntimeException("User has no team assigned");
        }
        return user.getTeamId();
    }

    /**
     * Get teamId or return null if header missing (for optional contexts).
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
     * Check if the current user (from header) is fired.
     */
    public boolean isCurrentUserFired(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) return false;
        try {
            int userId = Integer.parseInt(userIdHeader);
            return userRepository.findById(userId).map(User::isFired).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
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
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) return null;
        try {
            int userId = Integer.parseInt(userIdHeader);
            return userRepository.findById(userId).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
