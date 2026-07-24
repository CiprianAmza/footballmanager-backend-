package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent configuration pair used by a sensitivity run. */
public final class CanonicalScoringWeightSet {
    private final CompartmentEngineConfig compartment;
    private final MatchEngineConfig match;

    private CanonicalScoringWeightSet(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        this.compartment = compartment;
        this.match = match;
    }

    public static CanonicalScoringWeightSet baseline(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        return new CanonicalScoringWeightSet(copyCompartment(compartment), copyMatch(match));
    }

    public CanonicalScoringWeightSet override(CanonicalScoringWeightOverride override) {
        if (override == null) throw new NullPointerException("override");
        String key = override.key();
        if (key.startsWith("compartment.rating.")) {
            switch (key.substring("compartment.rating.".length())) {
                case "score-scale" -> compartment.getRating().setScoreScale(override.value());
                case "context-coefficient-min" -> compartment.getRating().setContextCoefficientMin(override.value());
                case "context-coefficient-max" -> compartment.getRating().setContextCoefficientMax(override.value());
                default -> throw new IllegalArgumentException("unsupported override: " + key);
            }
        } else if (key.equals("match.role-weights.suitability-scale")) {
            match.getRoleWeights().setSuitabilityScale(override.value());
        } else if (key.equals("match.role-weights.overall-blend")) {
            match.getRoleWeights().setOverallBlend(override.value());
        } else if (key.equals("match.role-weights.role-blend")) {
            match.getRoleWeights().setRoleBlend(override.value());
        } else if (key.equals("match.instruction-weights.bonus-scale")) {
            match.getInstructionWeights().setBonusScale(override.value());
        } else if (key.equals("match.instruction-weights.conflict-penalty")) {
            match.getInstructionWeights().setConflictPenalty(override.value());
        } else if (key.startsWith("compartment.context-rules.")) {
            String[] parts = key.split("\\.", 4);
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            String source = parts[2];
            com.footballmanagergamesimulator.compartment.PlayerAttribute attribute =
                    com.footballmanagergamesimulator.compartment.PlayerAttribute.valueOf(parts[3]);
            Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double> row = compartment.getContextRules().get(source);
            if (row == null) throw new IllegalArgumentException("unknown context rule: " + source);
            row.put(attribute, override.value());
        } else if (key.startsWith("compartment.compartments.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 5 || !parts[3].equals("attributes")) throw new IllegalArgumentException("unsupported override: " + key);
            Compartment c = Compartment.valueOf(parts[2]);
            com.footballmanagergamesimulator.compartment.PlayerAttribute attribute =
                    com.footballmanagergamesimulator.compartment.PlayerAttribute.valueOf(parts[4]);
            compartment.getCompartments().get(c).getAttributes().put(attribute, override.value());
        } else if (key.startsWith("compartment.positions.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            var multipliers = compartment.getPositions().get(parts[2]);
            if (multipliers == null) throw new IllegalArgumentException("unknown position: " + parts[2]);
            setMultiplier(multipliers, parts[3], override.value());
        } else if (key.startsWith("compartment.roles.")) {
            String[] parts = key.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("unsupported override: " + key);
            var multipliers = compartment.getRoles().get(com.footballmanagergamesimulator.compartment.PlayerRole.valueOf(parts[2]));
            if (multipliers == null) throw new IllegalArgumentException("unknown role: " + parts[2]);
            setMultiplier(multipliers, parts[3], override.value());
        } else {
            throw new IllegalArgumentException("unsupported override: " + key);
        }
        return this;
    }

    public CompartmentEngineConfig compartment() { return compartment; }
    public MatchEngineConfig match() { return match; }

    private static void setMultiplier(CompartmentEngineConfig.CompartmentMultipliers multipliers, String axis, double value) {
        switch (axis) {
            case "attack" -> multipliers.setAttack(value);
            case "midfield" -> multipliers.setMidfield(value);
            case "defense" -> multipliers.setDefense(value);
            default -> throw new IllegalArgumentException("unknown multiplier axis: " + axis);
        }
    }

    private static CompartmentEngineConfig copyCompartment(CompartmentEngineConfig source) {
        if (source == null) throw new NullPointerException("compartment");
        CompartmentEngineConfig target = new CompartmentEngineConfig();
        target.setEnabled(source.isEnabled());
        target.setShadowEnabled(source.isShadowEnabled());
        target.getRating().setAttributeMin(source.getRating().getAttributeMin());
        target.getRating().setAttributeMax(source.getRating().getAttributeMax());
        target.getRating().setScoreScale(source.getRating().getScoreScale());
        target.getRating().setContextFactorMin(source.getRating().getContextFactorMin());
        target.getRating().setContextFactorMax(source.getRating().getContextFactorMax());
        target.getRating().setTotalContextMin(source.getRating().getTotalContextMin());
        target.getRating().setTotalContextMax(source.getRating().getTotalContextMax());
        target.getRating().setContextCoefficientMin(source.getRating().getContextCoefficientMin());
        target.getRating().setContextCoefficientMax(source.getRating().getContextCoefficientMax());
        target.setContextRules(copyNested(source.getContextRules()));
        Map<Compartment, CompartmentEngineConfig.CompartmentWeights> compartments = new LinkedHashMap<>();
        source.getCompartments().forEach((key, value) -> {
            CompartmentEngineConfig.CompartmentWeights copy = new CompartmentEngineConfig.CompartmentWeights();
            copy.setAttributes(new LinkedHashMap<>(value.getAttributes()));
            compartments.put(key, copy);
        });
        target.setCompartments(compartments);
        target.setPositions(copyMultipliers(source.getPositions()));
        target.setRoles(new LinkedHashMap<>(source.getRoles()));
        target.setDuties(new LinkedHashMap<>(source.getDuties()));
        target.setMentalities(new LinkedHashMap<>(source.getMentalities()));
        return target;
    }

    private static Map<String, CompartmentEngineConfig.CompartmentMultipliers> copyMultipliers(Map<String, CompartmentEngineConfig.CompartmentMultipliers> source) {
        Map<String, CompartmentEngineConfig.CompartmentMultipliers> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            CompartmentEngineConfig.CompartmentMultipliers copy = new CompartmentEngineConfig.CompartmentMultipliers();
            copy.setAttack(value.getAttack()); copy.setMidfield(value.getMidfield()); copy.setDefense(value.getDefense());
            result.put(key, copy);
        });
        return result;
    }

    private static MatchEngineConfig copyMatch(MatchEngineConfig source) {
        if (source == null) throw new NullPointerException("match");
        MatchEngineConfig target = new MatchEngineConfig();
        target.getPlayerValue().setScaleMultiplier(source.getPlayerValue().getScaleMultiplier());
        target.getPlayerValue().setRatingFloor(source.getPlayerValue().getRatingFloor());
        target.getPlayerValue().setRatingCeil(source.getPlayerValue().getRatingCeil());
        target.getPlayerValue().setMoraleNeutral(source.getPlayerValue().getMoraleNeutral());
        target.getPlayerValue().setMoraleSlope(source.getPlayerValue().getMoraleSlope());
        target.getPlayerValue().setFitnessFloor(source.getPlayerValue().getFitnessFloor());
        target.getPlayerValue().setDefaultFamiliarityPenalty(source.getPlayerValue().getDefaultFamiliarityPenalty());
        target.getPlayerValue().setWeights(copyNested(source.getPlayerValue().getWeights()));
        target.getPlayerValue().setFamiliarityPenalty(copyNested(source.getPlayerValue().getFamiliarityPenalty()));
        target.getRoleWeights().setOverallBlend(source.getRoleWeights().getOverallBlend());
        target.getRoleWeights().setRoleBlend(source.getRoleWeights().getRoleBlend());
        target.getRoleWeights().setSuitabilityScale(source.getRoleWeights().getSuitabilityScale());
        target.getRoleWeights().setAttributes(copyNested(source.getRoleWeights().getAttributes()));
        target.getInstructionWeights().setBonusScale(source.getInstructionWeights().getBonusScale());
        target.getInstructionWeights().setConflictPenalty(source.getInstructionWeights().getConflictPenalty());
        target.getInstructionWeights().setClampMin(source.getInstructionWeights().getClampMin());
        target.getInstructionWeights().setClampMax(source.getInstructionWeights().getClampMax());
        return target;
    }

    private static <K, V> Map<K, V> copyNested(Map<K, V> source) {
        Map<K, V> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                result.put(key, (V) copyNested((Map<Object, Object>) nested));
            } else result.put(key, value);
        });
        return result;
    }
}
