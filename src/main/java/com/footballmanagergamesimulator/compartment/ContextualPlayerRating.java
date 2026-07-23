package com.footballmanagergamesimulator.compartment;

import java.util.List;
import java.util.Map;

/** Explainable Attack/Midfield/Defense result of one pure player evaluation. */
public record ContextualPlayerRating(
        String position,
        String role,
        Duty duty,
        Map<Compartment, CompartmentBreakdown> compartments) {

    public ContextualPlayerRating {
        compartments = Map.copyOf(compartments);
    }

    public record CompartmentBreakdown(
            Compartment compartment,
            double baseScore,
            double rawContextualScore,
            double contextRatio,
            double contextualScore,
            double positionMultiplier,
            double roleMultiplier,
            double dutyMultiplier,
            double familiarityFactor,
            double fitnessFactor,
            double moraleFactor,
            double roleFit,
            double finalScore,
            List<AttributeContribution> attributes) {

        public CompartmentBreakdown {
            attributes = List.copyOf(attributes);
        }
    }

    public record AttributeContribution(
            PlayerAttribute attribute,
            int rawValue,
            double normalizedValue,
            double weight,
            double requestedContextCoefficient,
            double appliedContextCoefficient,
            double contextFactor,
            double baseContribution,
            double contextualContribution) {}
}
