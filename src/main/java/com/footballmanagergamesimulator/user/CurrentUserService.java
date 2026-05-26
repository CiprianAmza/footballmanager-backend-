package com.footballmanagergamesimulator.user;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Autowired
    private UserRepository userRepository;

    public Integer getUserIdOrNull(HttpServletRequest request) {
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(userIdHeader);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public User getUserOrNull(HttpServletRequest request) {
        Integer userId = getUserIdOrNull(request);
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    public User requireUser(HttpServletRequest request) {
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new RuntimeException("Missing X-User-Id header");
        }

        final int userId;
        try {
            userId = Integer.parseInt(userIdHeader);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid X-User-Id header");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }

    public long requireTeamId(HttpServletRequest request) {
        User user = requireUser(request);
        if (user.getTeamId() == null || user.getTeamId() <= 0) {
            throw new RuntimeException("User has no team assigned");
        }
        return user.getTeamId();
    }
}
