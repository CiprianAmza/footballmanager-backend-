package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure probability rules for the explicitly selected SHOOTER and the pressing trade-off. */
final class ShooterMatchMechanic {
    private static final double DISTRIBUTION_TOLERANCE = 1e-9;

    private final CompartmentEngineConfig config;

    ShooterMatchMechanic(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    boolean redCard(String pressing, double roll) {
        requireRoll(roll);
        return roll < pressingRule(pressing).getRedCardChance();
    }

    double goalChance(int longShots, String opponentPressing) {
        validateAttribute(longShots, "Long Shots");
        int exceptional = config.getRating().getExceptionalAttributeValue();
        double beforePressing;
        if (longShots == exceptional) {
            beforePressing = 1.0;
        } else {
            double ratio = longShots / (double) config.getRating().getAttributeMax();
            beforePressing = config.getShooter().getRegularLongShotsCeiling()
                    * Math.pow(ratio, config.getShooter().getRegularLongShotsExponent());
        }
        return clamp01(beforePressing * (1.0 - pressingRule(opponentPressing).getShotReduction()));
    }

    int sampleShotCount(int positioning, double roll) {
        validateAttribute(positioning, "Positioning");
        requireRoll(roll);
        List<Double> distribution = positioning == config.getRating().getExceptionalAttributeValue()
                ? config.getShooter().getExceptionalPositioningShotDistribution()
                : config.getShooter().getStandardShotDistribution();
        validateDistribution(distribution);
        double cumulative = 0.0;
        for (int shots = 0; shots < distribution.size(); shots++) {
            cumulative += distribution.get(shots);
            if (roll < cumulative) return shots;
        }
        return distribution.size() - 1;
    }

    private CompartmentEngineConfig.PressingRule pressingRule(String pressing) {
        if (pressing == null || pressing.isBlank()) {
            throw new IllegalArgumentException("pressing must not be blank");
        }
        String requested = canonicalPressing(pressing);
        return config.getShooter().getPressing().entrySet().stream()
                .filter(entry -> canonicalPressing(entry.getKey()).equals(requested))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing SHOOTER pressing rule: " + pressing));
    }

    private static String canonicalPressing(String value) {
        return value == null ? "" : value.replace(" ", "").replace("-", "")
                .replace("_", "").trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void validateAttribute(int value, String name) {
        int min = config.getRating().getAttributeMin();
        int exceptional = config.getRating().getExceptionalAttributeValue();
        if (value < min || value > exceptional) {
            throw new IllegalArgumentException(name + " must be in [" + min + ',' + exceptional + "]");
        }
    }

    private static void validateDistribution(List<Double> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalStateException("shot distribution must not be empty");
        }
        double sum = 0.0;
        for (Double probability : distribution) {
            if (probability == null || !Double.isFinite(probability) || probability < 0.0) {
                throw new IllegalStateException("shot distribution must contain finite non-negative values");
            }
            sum += probability;
        }
        if (Math.abs(sum - 1.0) > DISTRIBUTION_TOLERANCE) {
            throw new IllegalStateException("shot distribution must sum to 1.0");
        }
    }

    private static void requireRoll(double roll) {
        if (!Double.isFinite(roll) || roll < 0.0 || roll >= 1.0) {
            throw new IllegalArgumentException("random roll must be in [0,1)");
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
