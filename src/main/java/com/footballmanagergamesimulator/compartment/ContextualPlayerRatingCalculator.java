package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.CompartmentMultipliers;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.Rating;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.footballmanagergamesimulator.compartment.ContextualPlayerRating.AttributeContribution;
import static com.footballmanagergamesimulator.compartment.ContextualPlayerRating.CompartmentBreakdown;

/**
 * Pure, side-effect-free contextual player ratings, in two levels:
 * <ol>
 *   <li>{@link PlayerSpecialProfile}: attack / midfield / defense from the attributes at the
 *       natural position, independent of tactic;</li>
 *   <li>this calculator: the tactic applied to that profile &mdash; the attributes the tactic
 *       favours (context coefficients), the position played, role, duty, familiarity (the loss
 *       off-position), fitness, morale, role fit.</li>
 * </ol>
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
        return rate(profile(input), input);
    }

    /** Level 1 for this input: the special profile at the player's natural position. */
    public PlayerSpecialProfile profile(PlayerRatingInput input) {
        return PlayerSpecialProfile.of(input.attributes(), input.naturalPosition(), config);
    }

    /** Level 2: the tactic in {@code input} applied to the player's special profile. */
    public ContextualPlayerRating rate(PlayerSpecialProfile profile, PlayerRatingInput input) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(input, "input");
        Map<Compartment, CompartmentBreakdown> results = new EnumMap<>(Compartment.class);
        for (Compartment compartment : Compartment.values()) {
            results.put(compartment, rateCompartment(profile.compartments().get(compartment), input));
        }
        return new ContextualPlayerRating(input.position(), input.role(), input.duty(), results);
    }

    private CompartmentBreakdown rateCompartment(PlayerSpecialProfile.CompartmentBase level1,
                                                 PlayerRatingInput input) {
        Rating rating = config.getRating();
        Compartment compartment = level1.compartment();
        double base = level1.baseScore();
        double rawContextual = 0;
        List<AttributeContribution> contributions = new ArrayList<>();
        for (PlayerSpecialProfile.AttributeBase part : level1.attributes()) {
            PlayerAttribute attribute = part.attribute();
            double requestedK = input.contextCoefficients().getOrDefault(attribute, 0.0);
            if (!Double.isFinite(requestedK)) {
                throw new IllegalArgumentException("Context coefficient must be finite: " + attribute);
            }
            double appliedK = clamp(requestedK,
                    rating.getContextCoefficientMin(), rating.getContextCoefficientMax());
            double factor = contextFactor(part.normalizedValue(), appliedK,
                    rating.getContextFactorMin(), rating.getContextFactorMax());
            double contextualContribution = part.baseContribution() * factor;
            rawContextual += contextualContribution;
            contributions.add(new AttributeContribution(attribute, part.rawValue(), part.normalizedValue(),
                    part.weight(), requestedK, appliedK, factor, part.baseContribution(), contextualContribution));
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
        if (!Double.isFinite(normalizedAttribute) || normalizedAttribute < 0) {
            throw new IllegalArgumentException("normalized attribute must be non-negative");
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
