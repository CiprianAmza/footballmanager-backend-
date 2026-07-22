package com.footballmanagergamesimulator.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManagerSetupRequest(
        @NotBlank @Size(max = 100) String managerName,
        @Min(18) @Max(90) int managerAge,
        Long teamId,
        boolean freeAgent
) {
}
