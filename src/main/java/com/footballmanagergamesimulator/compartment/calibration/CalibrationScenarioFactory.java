package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;

/** Builds a deterministic fixture that activates the consumer named by a catalog leaf. */
public final class CalibrationScenarioFactory {
    private CalibrationScenarioFactory() {}

    public static ScoringSensitivityScenario forWeight(CanonicalScoringWeightKey leaf) {
        return build(leaf, 200);
    }

    public static ScoringSensitivityScenario forWeightSmoke(CanonicalScoringWeightKey leaf) {
        return build(leaf, 1);
    }

    private static ScoringSensitivityScenario build(CanonicalScoringWeightKey leaf, int seasons) {
        if (leaf == null) throw new NullPointerException("leaf");
        var base = CalibrationScenarioFixtures.selectedWeights();
        String path = leaf.path();
        var team = base.baselineTeam();
        if (path.startsWith("match.role-weights.attributes.")) {
            String roleAndAttribute = path.substring("match.role-weights.attributes.".length());
            int separator = roleAndAttribute.lastIndexOf('.');
            if (separator <= 0) throw new IllegalArgumentException("invalid role attribute path: " + path);
            PlayerRole role = PlayerRole.fromDisplayName(roleAndAttribute.substring(0, separator))
                    .orElseThrow(() -> new IllegalArgumentException("unknown role: " + roleAndAttribute.substring(0, separator)));
            String attribute = parseRoleAttribute(roleAndAttribute.substring(separator + 1));
            team = team.withRole(role).withRoleAttribute(attribute);
        } else if (path.startsWith("compartment.positions.")) {
            String position = path.substring("compartment.positions.".length(), path.indexOf('.', "compartment.positions.".length()));
            team = team.withPosition(PlayerPosition.require(position));
        } else if (path.startsWith("compartment.roles.")) {
            String role = path.substring("compartment.roles.".length(), path.indexOf('.', "compartment.roles.".length()));
            team = team.withRole(PlayerRole.valueOf(role));
        } else if (path.contains(".attributes.")) {
            team = team.withAttribute(parseAttribute(path.substring(path.lastIndexOf('.') + 1)));
        } else if (path.contains("mentalities.")) {
            String name = path.substring(path.indexOf("mentalities.") + 12);
            name = name.substring(0, name.indexOf('.'));
            team = team.withMentality(Mentality.valueOf(name));
        } else if (path.contains("work-rate.instructions.")) {
            String instruction = path.substring(path.indexOf("work-rate.instructions.") + "work-rate.instructions.".length());
            instruction = instruction.substring(0, instruction.indexOf('.'));
            var forwardInstruction = com.footballmanagergamesimulator.compartment.ForwardInstruction.valueOf(instruction);
            team = forwardInstruction == com.footballmanagergamesimulator.compartment.ForwardInstruction.STAY_FORWARD
                    ? team.withStayForward() : team.withForwardInstruction(forwardInstruction);
            team = team.withTacticalContext(context -> new TacticalContextInput(context.mentality(), context.tempo(),
                    context.passingType(), context.defensiveLine(), context.pressing(), context.width(), context.playerInstructions()));
        } else if (path.contains("work-rate.traits.")) {
            team = team.withTrait(com.footballmanagergamesimulator.compartment.PlayerTrait.REFUSES_DEFENSIVE_WORK);
        } else if (path.contains("familiarity-penalty")) {
            String pair = path.substring(path.indexOf("familiarity-penalty.") + "familiarity-penalty.".length());
            String[] positions = pair.split("\\.");
            if (positions.length < 2) throw new IllegalArgumentException("invalid familiarity penalty path: " + path);
            team = team.withoutPersistentFamiliarity().withPosition(PlayerPosition.require(positions[1])).withNaturalPosition(
                    PlayerPosition.require(positions[0]), PlayerPosition.require(positions[1]));
        } else if (path.endsWith("context-coefficient-min") || path.endsWith("context-coefficient-max")) {
            team = applyContextActivator(team, "compartment.context-rules.instructionclosedownless.CONCENTRATION");
        } else if (path.endsWith("context-factor-min") || path.endsWith("context-factor-max")) {
            team = applyContextActivator(team, "compartment.context-rules.mentalityveryattacking.OFF_THE_BALL")
                    .withAttributeValue(PlayerAttribute.OFF_THE_BALL, 1)
                    .withAttributeValue(PlayerAttribute.COMPOSURE, 1);
        } else if (path.endsWith("total-context-min") || path.endsWith("total-context-max")) {
            team = applyContextActivator(team, "compartment.context-rules.mentalityveryattacking.OFF_THE_BALL")
                    .withAttributeValue(PlayerAttribute.OFF_THE_BALL, 1)
                    .withAttributeValue(PlayerAttribute.COMPOSURE, 1);
        } else if (path.endsWith("fitness-floor")) {
            team = team.withFitness(75.0);
        } else if (path.endsWith("morale-neutral") || path.endsWith("morale-slope")) {
            team = team.withMorale(100.0);
        } else if (path.equals("compartment.exposure.cb-recovery-pace-cap")) {
            team = team.withCenterBackAttribute(PlayerAttribute.PACE, 11)
                    .withTrait(com.footballmanagergamesimulator.compartment.PlayerTrait.REFUSES_DEFENSIVE_WORK);
        } else if (path.startsWith("compartment.exposure.")) {
            team = team.withTrait(com.footballmanagergamesimulator.compartment.PlayerTrait.REFUSES_DEFENSIVE_WORK);
        } else if (path.contains("context-rules.")) {
            team = applyContextActivator(team, path);
        }
        return new ScoringSensitivityScenario("activator-" + path, team, base.opponent(), base.seed(), seasons);
    }

    private static PlayerAttribute parseAttribute(String value) {
        String normalized = value.toUpperCase().replace(' ', '_').replace('-', '_');
        try { return PlayerAttribute.valueOf(normalized); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unknown calibration attribute: " + value, exception); }
    }

    private static String parseRoleAttribute(String value) {
        String normalized = value.toUpperCase().replace(' ', '_').replace('-', '_');
        return com.footballmanagergamesimulator.service.PlayerSkillsService.GETTER_MAP.keySet().stream()
                .filter(candidate -> candidate.toUpperCase().replace(' ', '_').replace('-', '_').equals(normalized))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown calibration role attribute: " + value));
    }

    private static CalibrationTeam applyContextActivator(CalibrationTeam team, String path) {
        String key = path.substring(path.indexOf("context-rules.") + "context-rules.".length());
        String row = key.substring(0, key.indexOf('.'));
        return team.withTacticalContext(context -> {
            String value = switch (row) {
                case "mentalityattacking" -> "Attacking";
                case "mentalityveryattacking" -> "Very Attacking";
                case "mentalitydefensive" -> "Defensive";
                case "mentalityverydefensive" -> "Very Defensive";
                case "tempohigher" -> "Higher";
                case "tempomuchhigher" -> "Much Higher";
                case "tempolower" -> "Lower";
                case "tempomuchlower" -> "Much Lower";
                case "passingshort" -> "Short";
                case "passinglong" -> "Long";
                case "linehigh" -> "High";
                case "linedeep" -> "Deep";
                case "pressinghigh" -> "High";
                case "pressinglow" -> "Low";
                case "widthwide" -> "Wide";
                case "widthnarrow" -> "Narrow";
                case "instructionmarktighter" -> "Mark Tighter";
                case "instructionclosedownmore" -> "Close Down More";
                case "instructionclosedownless" -> "Close Down Less";
                case "instructiontackleharder" -> "Tackle Harder";
                case "instructionstayonfeet" -> "Stay On Feet";
                case "instructioneaseofftackles" -> "Ease Off Tackles";
                case "instructiongetfurtherforward" -> "Get Further Forward";
                case "instructionholdposition" -> "Hold Position";
                case "instructionshootmoreoften" -> "Shoot More Often";
                case "instructionshootlessoften" -> "Shoot Less Often";
                case "instructiondribblemore" -> "Dribble More";
                case "instructiondribbleless" -> "Dribble Less";
                case "instructionroamfromposition" -> "Roam From Position";
                case "instructionsitnarrower" -> "Sit Narrower";
                case "instructionstaywider" -> "Stay Wider";
                case "instructionmoveintochannels" -> "Move Into Channels";
                case "instructiondropdeeper" -> "Drop Deeper";
                case "instructionpassitshorter" -> "Pass It Shorter";
                case "instructiontrymoredirectpasses" -> "Try More Direct Passes";
                case "instructioncrossfrombyline" -> "Cross From Byline";
                case "instructioncrossfromdeep" -> "Cross From Deep";
                case "instructionplaythroughballs" -> "Play Through Balls";
                default -> throw new IllegalArgumentException("unhandled context rule: " + path);
            };
            if (row.startsWith("mentality")) return new TacticalContextInput(value, context.tempo(), context.passingType(), context.defensiveLine(), context.pressing(), context.width(), context.playerInstructions());
            if (row.startsWith("tempo")) return new TacticalContextInput(context.mentality(), value, context.passingType(), context.defensiveLine(), context.pressing(), context.width(), context.playerInstructions());
            if (row.startsWith("passing")) return new TacticalContextInput(context.mentality(), context.tempo(), value, context.defensiveLine(), context.pressing(), context.width(), context.playerInstructions());
            if (row.startsWith("line")) return new TacticalContextInput(context.mentality(), context.tempo(), context.passingType(), value, context.pressing(), context.width(), context.playerInstructions());
            if (row.startsWith("pressing")) return new TacticalContextInput(context.mentality(), context.tempo(), context.passingType(), context.defensiveLine(), value, context.width(), context.playerInstructions());
            if (row.startsWith("width")) return new TacticalContextInput(context.mentality(), context.tempo(), context.passingType(), context.defensiveLine(), context.pressing(), value, context.playerInstructions());
            if (row.startsWith("instruction")) return new TacticalContextInput(context.mentality(), context.tempo(), context.passingType(), context.defensiveLine(), context.pressing(), context.width(), java.util.List.of(value));
            throw new IllegalArgumentException("unhandled context rule: " + path);
        });
    }
}
