package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;

import java.util.Objects;

/** Entity-free inputs for a reproducible sensitivity run. */
public record ScoringSensitivityScenario(
        String name,
        CanonicalRuntimeTeamInput baselineTeam,
        CanonicalRuntimeTeamInput opponent,
        long seed,
        int seasons) {
    public ScoringSensitivityScenario {
        name = Objects.requireNonNull(name, "name");
        baselineTeam = Objects.requireNonNull(baselineTeam, "baselineTeam");
        opponent = Objects.requireNonNull(opponent, "opponent");
        if (seasons < 1) throw new IllegalArgumentException("seasons must be positive");
    }
    public MatchVenue venueFor(int match) { return MatchVenue.HOME; }
}
