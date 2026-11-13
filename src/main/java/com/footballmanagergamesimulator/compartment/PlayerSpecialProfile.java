package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.CompartmentWeights;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.Rating;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Level 1 of the match engine: what a player is worth in each compartment (attack, midfield,
 * defense), read from his attributes at his <b>natural</b> position, <b>independent of tactic</b>.
 * The special rating is the combination of these three values weighted by the natural position
 * ({@code PlayerSkillsService.computeSpecialRating}).
 *
 * <p>Level 2 ({@link ContextualPlayerRatingCalculator#rate(PlayerSpecialProfile, PlayerRatingInput)})
 * starts from this profile and applies the tactic: the position actually played and the loss
 * off-position, role, duty, the attributes the tactic favours, fitness, morale.
 */
public record PlayerSpecialProfile(String naturalPosition, Map<Compartment, CompartmentBase> compartments) {

    public PlayerSpecialProfile {
        compartments = Map.copyOf(compartments);
    }

    /** One compartment's value before any tactic, with the attributes it is made of. */
    public record CompartmentBase(Compartment compartment, double baseScore, List<AttributeBase> attributes) {
        public CompartmentBase {
            attributes = List.copyOf(attributes);
        }
    }

    /** One attribute's share of a compartment's base value. */
    public record AttributeBase(PlayerAttribute attribute, int rawValue, double normalizedValue,
                                double weight, double baseContribution) {}

    public double baseScore(Compartment compartment) {
        return compartments.get(compartment).baseScore();
    }

    /** Builds the profile from the attributes at the natural position. */
    public static PlayerSpecialProfile of(Map<PlayerAttribute, Integer> attributes, String naturalPosition,
                                          CompartmentEngineConfig config) {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(config, "config");
        Rating rating = config.getRating();
        Map<Compartment, CompartmentBase> result = new EnumMap<>(Compartment.class);
        for (Compartment compartment : Compartment.values()) {
            CompartmentWeights weights = weightsFor(config, naturalPosition, compartment);
            if (weights == null || weights.getAttributes().isEmpty()) {
                throw new IllegalStateException("No attribute weights configured for " + compartment);
            }
            double base = 0;
            List<AttributeBase> parts = new ArrayList<>();
            for (Map.Entry<PlayerAttribute, Double> rule : weights.getAttributes().entrySet()) {
                Integer raw = attributes.get(rule.getKey());
                if (raw == null) {
                    throw new IllegalArgumentException("Missing configured attribute: " + rule.getKey());
                }
                double normalized = normalize(raw, rating);
                double contribution = rating.getScoreScale() * rule.getValue() * normalized;
                base += contribution;
                parts.add(new AttributeBase(rule.getKey(), raw, normalized, rule.getValue(), contribution));
            }
            result.put(compartment, new CompartmentBase(compartment, base, parts));
        }
        return new PlayerSpecialProfile(naturalPosition, result);
    }

    private static CompartmentWeights weightsFor(CompartmentEngineConfig config, String position,
                                                 Compartment compartment) {
        Map<Compartment, CompartmentWeights> override = position == null
                ? null : config.getPositionCompartmentOverrides().get(position);
        if (override != null && override.containsKey(compartment)) return override.get(compartment);
        return config.getCompartments().get(compartment);
    }

    /**
     * Every point counts: the scale keeps going past attribute-max (19), so 20 rates above
     * 19 and 21 above 20 (normalized values above 1).
     */
    private static double normalize(int attribute, Rating rating) {
        int min = rating.getAttributeMin();
        int max = rating.getAttributeMax();
        if (max <= min) throw new IllegalArgumentException("attribute max must exceed min");
        if (attribute < min) {
            throw new IllegalArgumentException("attribute must be at least " + min + ": " + attribute);
        }
        return (attribute - min) / (double) (max - min);
    }
}
