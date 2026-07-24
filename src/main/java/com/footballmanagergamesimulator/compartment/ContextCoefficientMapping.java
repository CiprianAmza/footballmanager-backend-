package com.footballmanagergamesimulator.compartment;

import java.util.List;
import java.util.Map;

/** Explainable result of tactic/instruction to contextual coefficient (K) mapping. */
public record ContextCoefficientMapping(
        Map<PlayerAttribute, Double> coefficients,
        List<Contribution> contributions,
        List<Clamp> clamps) {

    public ContextCoefficientMapping {
        coefficients = Map.copyOf(coefficients);
        contributions = List.copyOf(contributions);
        clamps = List.copyOf(clamps);
    }

    public record Contribution(String source, PlayerAttribute attribute, double delta) {}
    public record Clamp(PlayerAttribute attribute, double requested, double applied) {}
}
