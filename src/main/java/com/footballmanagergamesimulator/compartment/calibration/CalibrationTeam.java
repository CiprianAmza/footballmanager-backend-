package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;

import java.util.List;
import java.util.Objects;

public record CalibrationTeam(Mentality mentality, List<CalibrationPlayer> players) {
    public CalibrationTeam {
        mentality = Objects.requireNonNull(mentality, "mentality");
        players = List.copyOf(players);
        if (players.size() != 11) throw new IllegalArgumentException("calibration team must contain 11 players");
    }
}
