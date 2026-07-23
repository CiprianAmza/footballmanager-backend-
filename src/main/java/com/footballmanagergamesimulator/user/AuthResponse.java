package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfile;

import java.util.Arrays;
import java.util.List;

public record AuthResponse(
        boolean success,
        Integer userId,
        String username,
        String email,
        Long teamId,
        Long managerId,
        CareerRole careerRole,
        Long profileId,
        List<String> roles,
        boolean regentEnabled,
        String error
) {
    public static AuthResponse success(User user, PersonProfile profile) {
        return success(user, profile, false);
    }

    public static AuthResponse success(User user, PersonProfile profile, boolean regentEnabled) {
        List<String> roles = user.getRoles() == null ? List.of() : Arrays.stream(user.getRoles().split(","))
                .map(String::trim).filter(role -> !role.isBlank()).toList();
        return new AuthResponse(true, user.getId(), user.getUsername(), user.getEmail(), user.getTeamId(),
                user.getManagerId(), user.getCareerRole(), profile == null ? null : profile.getId(), roles,
                regentEnabled, null);
    }

    public static AuthResponse failure(String error) {
        return new AuthResponse(false, null, null, null, null, null, null, null, List.of(), false, error);
    }
}
