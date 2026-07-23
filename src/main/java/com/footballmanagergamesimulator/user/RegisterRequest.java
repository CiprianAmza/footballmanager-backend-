package com.footballmanagergamesimulator.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+") @Size(min = 3, max = 64) String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 128) String password,
        @NotBlank @Size(min = 2, max = 100) String displayName,
        @NotNull CareerRole careerRole,
        Long startingWealth
) {
    public RegisterRequest(String username, String email, String password,
                           String displayName, CareerRole careerRole) {
        this(username, email, password, displayName, careerRole, null);
    }
}
