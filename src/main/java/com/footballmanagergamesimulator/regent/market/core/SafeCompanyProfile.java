package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.util.Objects;

/** Versioned parameters for the low-volatility company model. */
public record SafeCompanyProfile(BigDecimal maximumDailyMagnitude, BigDecimal positiveProbability,
                                 String algorithmVersion) {
    public SafeCompanyProfile {
        maximumDailyMagnitude = requireRange(maximumDailyMagnitude, BigDecimal.ZERO, new BigDecimal("0.01"),
                "maximumDailyMagnitude");
        positiveProbability = requireRange(positiveProbability, new BigDecimal("0.50"), BigDecimal.ONE,
                "positiveProbability");
        if (positiveProbability.compareTo(new BigDecimal("0.50")) <= 0) {
            throw new IllegalArgumentException("positiveProbability must be greater than 0.50");
        }
        algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
    }

    static BigDecimal requireRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be in [" + minimum + ", " + maximum + "]");
        }
        return value;
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
