package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.Duty;

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
            return new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(),
                    com.footballmanagergamesimulator.compartment.PlayerRole.SHADOW_STRIKER,
                    com.footballmanagergamesimulator.compartment.Duty.ATTACK, p.attributes(),
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(),
                    java.util.Map.of(), p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context());
        }).toList());
    }

    public CalibrationTeam withMentality(Mentality value) {
        return new CalibrationTeam(value, players);
    }

    public CalibrationTeam withRole(com.footballmanagergamesimulator.compartment.PlayerRole role) {
        com.footballmanagergamesimulator.compartment.PlayerPosition target = switch (role) {
            case GOALKEEPER, SWEEPER_KEEPER -> com.footballmanagergamesimulator.compartment.PlayerPosition.GK;
            case CENTRAL_DEFENDER, BALL_PLAYING_DEFENDER, NO_NONSENSE_DEFENDER -> com.footballmanagergamesimulator.compartment.PlayerPosition.DC;
            case FULL_BACK, WING_BACK, INVERTED_WING_BACK -> com.footballmanagergamesimulator.compartment.PlayerPosition.DL;
            case WINGER, INSIDE_FORWARD, WIDE_MIDFIELDER, INVERTED_WINGER -> com.footballmanagergamesimulator.compartment.PlayerPosition.ML;
            case ADVANCED_FORWARD, POACHER, TARGET_MAN, DEEP_LYING_FORWARD, PRESSING_FORWARD, COMPLETE_FORWARD -> com.footballmanagergamesimulator.compartment.PlayerPosition.ST;
            default -> com.footballmanagergamesimulator.compartment.PlayerPosition.MC;
        };
        Duty duty = role == com.footballmanagergamesimulator.compartment.PlayerRole.GOALKEEPER
                || role == com.footballmanagergamesimulator.compartment.PlayerRole.SWEEPER_KEEPER
                || role == com.footballmanagergamesimulator.compartment.PlayerRole.CENTRAL_DEFENDER
                ? Duty.DEFEND : Duty.SUPPORT;
        return replaceOne(target, role, duty);
    }

    public CalibrationTeam withAttribute(com.footballmanagergamesimulator.compartment.PlayerAttribute attribute) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            CalibrationPlayer p = players.get(i);
            java.util.EnumMap<com.footballmanagergamesimulator.compartment.PlayerAttribute, Integer> values =
                    new java.util.EnumMap<>(p.attributes());
            values.put(attribute, 8 + i);
            copy.add(new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), values,
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                    p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context()));
        }
        return new CalibrationTeam(mentality, copy);
    }

    private CalibrationTeam replaceOne(com.footballmanagergamesimulator.compartment.PlayerPosition target,
                                       com.footballmanagergamesimulator.compartment.PlayerRole role, Duty duty) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>(players);
        int index = 0;
        for (int i = 0; i < copy.size(); i++) if (copy.get(i).position() == target) { index = i; break; }
        CalibrationPlayer p = copy.get(index);
        copy.set(index, new CalibrationPlayer(p.playerId(), target, p.occurrence(), role, duty, p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context()));
        return new CalibrationTeam(mentality, copy);
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
