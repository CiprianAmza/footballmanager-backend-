package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerPosition;

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

    public CalibrationTeam withFitness(double fitness) {
        return new CalibrationTeam(mentality, players.stream().map(p -> new CalibrationPlayer(p.playerId(), p.position(),
                p.occurrence(), p.role(), p.duty(), p.attributes(), p.roleAttributeWeights(), fitness, p.morale(),
                p.positionFamiliarity(), p.roleFamiliarity(), p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(),
                p.context())).toList());
    }

    public CalibrationTeam withStayForward() {
        return new CalibrationTeam(mentality, players.stream().map(p -> p.position().code().equals("ST")
                || p.position().code().equals("DC")
                ? copy(p, p.morale(), p.traits(), ForwardInstruction.STAY_FORWARD)
                : p).toList());
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

    public CalibrationTeam withAttributeValue(com.footballmanagergamesimulator.compartment.PlayerAttribute attribute, int value) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>();
        for (CalibrationPlayer p : players) {
            java.util.EnumMap<com.footballmanagergamesimulator.compartment.PlayerAttribute, Integer> values =
                    new java.util.EnumMap<>(p.attributes());
            values.put(attribute, value);
            copy.add(new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), values,
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                    p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context(), p.primaryPosition(),
                    p.namedAttributes()));
        }
        return new CalibrationTeam(mentality, copy);
    }

    public CalibrationTeam withRoleAttribute(String attribute) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            CalibrationPlayer p = players.get(i);
            java.util.LinkedHashMap<String, Integer> values = new java.util.LinkedHashMap<>(p.namedAttributes());
            values.put(attribute, 8 + i);
            copy.add(new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                    p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                    p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context(), p.primaryPosition(), values));
        }
        return new CalibrationTeam(mentality, copy);
    }

    public CalibrationTeam withCenterBackAttribute(com.footballmanagergamesimulator.compartment.PlayerAttribute attribute, int value) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>(players);
        for (int i = 0; i < copy.size(); i++) {
            CalibrationPlayer p = copy.get(i);
            if (p.position() == com.footballmanagergamesimulator.compartment.PlayerPosition.DC) {
                java.util.EnumMap<com.footballmanagergamesimulator.compartment.PlayerAttribute, Integer> values =
                        new java.util.EnumMap<>(p.attributes());
                values.put(attribute, value);
                copy.set(i, new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), values,
                        p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                        p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context()));
                return new CalibrationTeam(mentality, copy);
            }
        }
        throw new IllegalArgumentException("calibration team has no center back");
    }

    public CalibrationTeam withPosition(com.footballmanagergamesimulator.compartment.PlayerPosition position) {
        if (players.stream().anyMatch(player -> player.position() == position)) return this;
        com.footballmanagergamesimulator.compartment.PlayerRole role = switch (position) {
            case GK -> com.footballmanagergamesimulator.compartment.PlayerRole.GOALKEEPER;
            case DC -> com.footballmanagergamesimulator.compartment.PlayerRole.CENTRAL_DEFENDER;
            case DL, DR, WBL, WBR -> com.footballmanagergamesimulator.compartment.PlayerRole.FULL_BACK;
            case DM, MC, AMC -> com.footballmanagergamesimulator.compartment.PlayerRole.CENTRAL_MIDFIELDER;
            case ML, MR, AML, AMR -> com.footballmanagergamesimulator.compartment.PlayerRole.WINGER;
            case ST -> com.footballmanagergamesimulator.compartment.PlayerRole.POACHER;
        };
        Duty duty = role == com.footballmanagergamesimulator.compartment.PlayerRole.GOALKEEPER
                || role == com.footballmanagergamesimulator.compartment.PlayerRole.CENTRAL_DEFENDER
                ? Duty.DEFEND : Duty.SUPPORT;
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>(players);
        int replacement = 0;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).position() != com.footballmanagergamesimulator.compartment.PlayerPosition.GK) {
                replacement = i;
                break;
            }
        }
        CalibrationPlayer p = copy.get(replacement);
        copy.set(replacement, new CalibrationPlayer(p.playerId(), position, 1, role, duty, p.attributes(), p.roleAttributeWeights(),
                p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(), p.leftFoot(), p.rightFoot(),
                p.traits(), p.instruction(), p.context()));
        java.util.EnumMap<com.footballmanagergamesimulator.compartment.PlayerPosition, Integer> occurrences =
                new java.util.EnumMap<>(com.footballmanagergamesimulator.compartment.PlayerPosition.class);
        for (int i = 0; i < copy.size(); i++) {
            CalibrationPlayer current = copy.get(i);
            int occurrence = occurrences.merge(current.position(), 1, Integer::sum);
            if (current.occurrence() != occurrence) {
                copy.set(i, new CalibrationPlayer(current.playerId(), current.position(), occurrence, current.role(), current.duty(),
                        current.attributes(), current.roleAttributeWeights(), current.fitness(), current.morale(),
                        current.positionFamiliarity(), current.roleFamiliarity(), current.leftFoot(), current.rightFoot(),
                        current.traits(), current.instruction(), current.context()));
            }
        }
        return new CalibrationTeam(mentality, copy);
    }

    public CalibrationTeam withTacticalContext(java.util.function.UnaryOperator<TacticalContextInput> update) {
        return new CalibrationTeam(mentality, players.stream().map(p -> new CalibrationPlayer(p.playerId(), p.position(),
                p.occurrence(), p.role(), p.duty(), p.attributes(), p.roleAttributeWeights(), p.fitness(), p.morale(),
                p.positionFamiliarity(), p.roleFamiliarity(), p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(),
                update.apply(p.context()))).toList());
    }

    public CalibrationTeam withPlayerInstructionContext(String instruction) {
        return withTacticalContext(context -> new TacticalContextInput(context.mentality(), context.tempo(),
                context.passingType(), context.defensiveLine(), context.pressing(), context.width(),
                java.util.List.of(instruction)));
    }

    public CalibrationTeam withForwardInstruction(ForwardInstruction instruction) {
        return new CalibrationTeam(mentality, players.stream().map(p -> p.position() == com.footballmanagergamesimulator.compartment.PlayerPosition.DC
                ? new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                p.leftFoot(), p.rightFoot(), p.traits(), instruction, p.context()) : p).toList());
    }

    public CalibrationTeam withTrait(com.footballmanagergamesimulator.compartment.PlayerTrait trait) {
        return new CalibrationTeam(mentality, players.stream().map(p -> new CalibrationPlayer(p.playerId(), p.position(),
                p.occurrence(), p.role(), p.duty(), p.attributes(), p.roleAttributeWeights(), p.fitness(), p.morale(),
                p.positionFamiliarity(), p.roleFamiliarity(), p.leftFoot(), p.rightFoot(),
                java.util.Set.of(trait), p.instruction(), p.context())).toList());
    }

    private CalibrationTeam replaceOne(com.footballmanagergamesimulator.compartment.PlayerPosition target,
                                       com.footballmanagergamesimulator.compartment.PlayerRole role, Duty duty) {
        java.util.ArrayList<CalibrationPlayer> copy = new java.util.ArrayList<>(players);
        int index = -1;
        for (int i = 0; i < copy.size(); i++) if (copy.get(i).position() == target) { index = i; break; }
        if (index < 0 && target != com.footballmanagergamesimulator.compartment.PlayerPosition.GK) {
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).position() != com.footballmanagergamesimulator.compartment.PlayerPosition.GK) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) throw new IllegalArgumentException("calibration team cannot place role " + role);
        CalibrationPlayer p = copy.get(index);
        copy.set(index, new CalibrationPlayer(p.playerId(), target, p.occurrence(), role, duty, p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context()));
        java.util.EnumMap<com.footballmanagergamesimulator.compartment.PlayerPosition, Integer> occurrences =
                new java.util.EnumMap<>(com.footballmanagergamesimulator.compartment.PlayerPosition.class);
        for (int i = 0; i < copy.size(); i++) {
            CalibrationPlayer current = copy.get(i);
            int occurrence = occurrences.merge(current.position(), 1, Integer::sum);
            if (current.occurrence() != occurrence) {
                copy.set(i, new CalibrationPlayer(current.playerId(), current.position(), occurrence, current.role(), current.duty(),
                        current.attributes(), current.roleAttributeWeights(), current.fitness(), current.morale(),
                        current.positionFamiliarity(), current.roleFamiliarity(), current.leftFoot(), current.rightFoot(),
                        current.traits(), current.instruction(), current.context()));
            }
        }
        return new CalibrationTeam(mentality, copy);
    }

    public CalibrationTeam withoutPersistentFamiliarity() {
        return new CalibrationTeam(mentality, players.stream().map(p -> new CalibrationPlayer(
                p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), java.util.Map.of(), java.util.Map.of(),
                p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context())).toList());
    }

    public CalibrationTeam withNaturalPosition(PlayerPosition naturalPosition, PlayerPosition usedPosition) {
        return new CalibrationTeam(mentality, players.stream().map(p -> p.position() == usedPosition
                ? new CalibrationPlayer(p.playerId(), p.position(), p.occurrence(), p.role(), p.duty(), p.attributes(),
                p.roleAttributeWeights(), p.fitness(), p.morale(), p.positionFamiliarity(), p.roleFamiliarity(),
                p.leftFoot(), p.rightFoot(), p.traits(), p.instruction(), p.context(), naturalPosition,
                p.namedAttributes()) : p).toList());
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
