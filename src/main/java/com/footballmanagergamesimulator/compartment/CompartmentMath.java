package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig.MentalityRule;

import java.util.Objects;

/** Pure previews of the configured compartment redistribution contract; no team/runtime wiring. */
public final class CompartmentMath {

    private CompartmentMath() {}

    public static Redistribution redistribute(double attack, double midfield, double defense,
                                              MentalityRule rule) {
        Objects.requireNonNull(rule, "rule");
        requireNonNegative(attack, "attack");
        requireNonNegative(midfield, "midfield");
        requireNonNegative(defense, "defense");
        double split = rule.getMidfieldToAttack() + rule.getMidfieldToDefense();
        if (Math.abs(split - 1.0) > 1e-9) {
            throw new IllegalArgumentException("midfield shares must sum to 1.0");
        }
        if (rule.getTransferShare() < 0 || rule.getTransferShare() > 1) {
            throw new IllegalArgumentException("transfer share must be in [0,1]");
        }

        double finalAttack = attack + midfield * rule.getMidfieldToAttack();
        double finalDefense = defense + midfield * rule.getMidfieldToDefense();
        if (rule.getTransferShare() > 0) {
            if (rule.getTransferFrom() == Compartment.DEFENSE && rule.getTransferTo() == Compartment.ATTACK) {
                double moved = finalDefense * rule.getTransferShare();
                finalDefense -= moved;
                finalAttack += moved;
            } else if (rule.getTransferFrom() == Compartment.ATTACK
                    && rule.getTransferTo() == Compartment.DEFENSE) {
                double moved = finalAttack * rule.getTransferShare();
                finalAttack -= moved;
                finalDefense += moved;
            } else {
                throw new IllegalArgumentException("only Attack<->Defense transfer is supported");
            }
        }
        return new Redistribution(finalAttack, finalDefense, rule.getOpenness());
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    public record Redistribution(double attack, double defense, double openness) {}
}
