package com.footballmanagergamesimulator.compartment.calibration;

import java.util.Objects;

/** Entity-free inputs for a reproducible sensitivity run. */
public record ScoringSensitivityScenario(
        String name,
        CalibrationTeam baselineTeam,
        CalibrationTeam opponent,
        long seed,
        int seasons) {
    public ScoringSensitivityScenario {
        name = Objects.requireNonNull(name, "name");
        baselineTeam = Objects.requireNonNull(baselineTeam, "baselineTeam");
        opponent = Objects.requireNonNull(opponent, "opponent");
        if (seasons < 1) throw new IllegalArgumentException("seasons must be positive");
    }
    public boolean isHomeMatch(int match) { return match % 38 < 19; }
}
