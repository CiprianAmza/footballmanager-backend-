package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.CompartmentMultipliers;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.CompartmentWeights;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.Rating;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.footballmanagergamesimulator.compartment.ContextualPlayerRating.AttributeContribution;
import static com.footballmanagergamesimulator.compartment.ContextualPlayerRating.CompartmentBreakdown;

/**
 * Pure, side-effect-free Phase 0/1 implementation of contextual player ratings.
 *
 * <p>There is no Spring annotation, repository access, random source, match score or runtime
 * integration here. Callers must supply the typed configuration and the complete player snapshot.
 */
public final class ContextualPlayerRatingCalculator {

    private final CompartmentEngineConfig config;

    public ContextualPlayerRatingCalculator(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public ContextualPlayerRating rate(PlayerRatingInput input) {
        Objects.requireNonNull(input, "input");
        Map<Compartment, CompartmentBreakdown> results = new EnumMap<>(Compartment.class);
        for (Compartment compartment : Compartment.values()) {
            results.put(compartment, rateCompartment(input, compartment));
        }
        return new ContextualPlayerRating(input.position(), input.role(), input.duty(), results);
    }

    private CompartmentBreakdown rateCompartment(PlayerRatingInput input, Compartment compartment) {
        Rating rating = config.getRating();
        CompartmentWeights weights = weightsFor(input.position(), compartment);
        if (weights == null || weights.getAttributes().isEmpty()) {
            throw new IllegalStateException("No attribute weights configured for " + compartment);
        }

        double base = 0;
        double rawContextual = 0;
        List<AttributeContribution> contributions = new ArrayList<>();
        for (Map.Entry<PlayerAttribute, Double> rule : weights.getAttributes().entrySet()) {
            PlayerAttribute attribute = rule.getKey();
            Integer raw = input.attributes().get(attribute);
            if (raw == null) {
                throw new IllegalArgumentException("Missing configured attribute: " + attribute);
            }
            double normalized = normalizeAttribute(raw, rating.getAttributeMin(), rating.getAttributeMax());
            double requestedK = input.contextCoefficients().getOrDefault(attribute, 0.0);
            if (!Double.isFinite(requestedK)) {
                throw new IllegalArgumentException("Context coefficient must be finite: " + attribute);
            }
            double appliedK = clamp(requestedK,
                    rating.getContextCoefficientMin(), rating.getContextCoefficientMax());
            double factor = contextFactor(normalized, appliedK,
                    rating.getContextFactorMin(), rating.getContextFactorMax());
            double baseContribution = rating.getScoreScale() * rule.getValue() * normalized;
            double contextualContribution = baseContribution * factor;
            base += baseContribution;
            rawContextual += contextualContribution;
            contributions.add(new AttributeContribution(attribute, raw, normalized, rule.getValue(),
                    requestedK, appliedK, factor, baseContribution, contextualContribution));
        }

        double rawRatio = base == 0 ? 1.0 : rawContextual / base;
        double contextRatio = clamp(rawRatio, rating.getTotalContextMin(), rating.getTotalContextMax());
        double contextualScore = base * contextRatio;
        double positionMultiplier = positionMultiplier(input.position(), compartment);
        double roleMultiplier = roleMultiplier(input.role(), compartment);
        double dutyMultiplier = dutyMultiplier(input.duty(), compartment);
        double familiarity = clamp(input.positionFamiliarity(), 0.0, 1.0);
        double fitness = Math.max(rating.getFitnessFloor(), clamp(input.fitness(), 0.0, 100.0) / 100.0);
        double morale = 1.0 + (clamp(input.morale(), 0.0, 100.0) - rating.getMoraleNeutral())
                * rating.getMoraleSlope();
        double roleFit = roleFit(input.roleSuitability(), rating.getRoleFitBase(), rating.getRoleFitRange());
        double finalScore = contextualScore * positionMultiplier * roleMultiplier * dutyMultiplier
                * familiarity * fitness * morale * roleFit;

        return new CompartmentBreakdown(compartment, base, rawContextual, contextRatio, contextualScore,
                positionMultiplier, roleMultiplier, dutyMultiplier, familiarity, fitness, morale,
                roleFit, finalScore, contributions);
    }

    private CompartmentWeights weightsFor(String position, Compartment compartment) {
        Map<Compartment, CompartmentWeights> override =
                config.getPositionCompartmentOverrides().get(position);
        if (override != null && override.containsKey(compartment)) return override.get(compartment);
        return config.getCompartments().get(compartment);
    }

    private double positionMultiplier(String position, Compartment compartment) {
        CompartmentMultipliers value = config.getPositions().get(position);
        return value == null ? config.getRating().getDefaultPositionMultiplier()
                : value.forCompartment(compartment);
    }

    private double roleMultiplier(String role, Compartment compartment) {
        CompartmentMultipliers value = PlayerRole.fromDisplayName(role)
                .map(config.getRoles()::get)
                .orElse(null);
        return value == null ? config.getRating().getDefaultRoleMultiplier()
                : value.forCompartment(compartment);
    }

    private double dutyMultiplier(Duty duty, Compartment compartment) {
        CompartmentMultipliers value = config.getDuties().get(duty);
        if (value == null) throw new IllegalStateException("No duty multipliers configured for " + duty);
        return value.forCompartment(compartment);
    }

    public static double normalizeAttribute(int attribute, int min, int max) {
        if (max <= min) throw new IllegalArgumentException("attribute max must exceed min");
        if (attribute < min || attribute > max) {
            throw new IllegalArgumentException("attribute must be in [" + min + "," + max + "]: " + attribute);
        }
        return (attribute - min) / (double) (max - min);
    }

    public static double contextFactor(double normalizedAttribute, double contextCoefficient,
                                       double minFactor, double maxFactor) {
        if (!Double.isFinite(normalizedAttribute) || normalizedAttribute < 0 || normalizedAttribute > 1) {
            throw new IllegalArgumentException("normalized attribute must be in [0,1]");
        }
        if (!Double.isFinite(contextCoefficient)) {
            throw new IllegalArgumentException("context coefficient must be finite");
        }
        return clamp(1.0 + contextCoefficient * (2.0 * normalizedAttribute - 1.0), minFactor, maxFactor);
    }

    public static double roleFit(double suitability, double base, double range) {
        return base + range * clamp(suitability, 0.0, 100.0) / 100.0;
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) throw new IllegalArgumentException("max must be >= min");
        return Math.max(min, Math.min(max, value));
    }
}
