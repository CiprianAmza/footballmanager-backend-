package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.util.Objects;

/** Parameters for the bounded high-volatility model. */
public record SpeculativeProfile(BigDecimal positiveFloor, String algorithmVersion) {
    public SpeculativeProfile {
        positiveFloor = Objects.requireNonNull(positiveFloor, "positiveFloor");
        if (positiveFloor.signum() <= 0) throw new IllegalArgumentException("positiveFloor must be positive");
        algorithmVersion = SafeCompanyProfile.requireText(algorithmVersion, "algorithmVersion");
    }
}
