package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.util.Objects;

/** Fractional return, for example 0.01 means +1%. */
public record DailyReturn(BigDecimal value, String algorithmVersion) {
    public DailyReturn {
        value = Objects.requireNonNull(value, "value");
        algorithmVersion = requireVersion(algorithmVersion);
    }

    private static String requireVersion(String value) {
        Objects.requireNonNull(value, "algorithmVersion");
        if (value.isBlank()) throw new IllegalArgumentException("algorithmVersion must not be blank");
        return value;
    }
}
