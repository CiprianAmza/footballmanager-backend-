package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;

import java.util.List;
import java.util.Objects;

public record CalibrationTeam(Mentality mentality, List<CalibrationPlayer> players) {
    public CalibrationTeam {
        mentality = Objects.requireNonNull(mentality, "mentality");
        players = List.copyOf(players);
        if (players.size() != 11) throw new IllegalArgumentException("calibration team must contain 11 players");
    }

    public CalibrationTeam withMorale(double morale) {
        return new CalibrationTeam(mentality, players.stream().map(p -> copy(p, morale, p.traits(), p.instruction())).toList());
    }

    public CalibrationTeam withStayForward() {
        return new CalibrationTeam(mentality, players.stream().map(p -> p.position().code().equals("ST")
                ? copy(p, p.morale(), java.util.Set.of(PlayerTrait.REFUSES_DEFENSIVE_WORK), ForwardInstruction.STAY_FORWARD)
                : p).toList());
    }

    /** Returns a measurement fixture with the AMC occupied by the hidden calibration role. */
    public CalibrationTeam withShadowStriker() {
        return new CalibrationTeam(mentality, players.stream().map(p -> {
            if (p.position() != com.footballmanagergamesimulator.compartment.PlayerPosition.AMC) return p;
            var key = new com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey(
                    p.position(), com.footballmanagergamesimulator.compartment.PlayerRole.SHADOW_STRIKER);
            return new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(),
                    com.footballmanagergamesimulator.compartment.PlayerRole.SHADOW_STRIKER,
                    com.footballmanagergamesimulator.compartment.Duty.ATTACK, p.attributes(),
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(),
                    java.util.Map.of(key, 20), p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context());
        }).toList());
    }

    public CalibrationTeam withMentality(Mentality value) {
        return new CalibrationTeam(value, players);
    }

    public CalibrationTeam withoutPersistentFamiliarity() {
        return new CalibrationTeam(mentality, players.stream().map(p -> new CalibrationPlayer(
                p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), java.util.Map.of(), java.util.Map.of(),
                p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context())).toList());
    }

    public CalibrationTeam withDefensiveLine(String line) {
        return new CalibrationTeam(mentality, players.stream().map(p -> {
            TacticalContextInput c = p.context();
            TacticalContextInput updated = new TacticalContextInput(c.mentality(), c.tempo(), c.passingType(), line,
                    c.pressing(), c.width(), c.playerInstructions());
            return new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                    p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), updated);
        }).toList());
    }

    private static CalibrationPlayer copy(CalibrationPlayer p, double morale, java.util.Set<PlayerTrait> traits,
                                          ForwardInstruction instruction) {
        return new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                p.roleAttributeWeights(), p.fitness(), morale, p.positionFamiliarity(), p.roleFamiliarity(),
                p.leftFoot(), p.rightFoot(), traits, instruction, p.context());
    }
}
