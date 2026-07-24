package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;

/** Limited market noise around the independently calculated club valuation. */
public record ClubEquityProfile(BigDecimal maximumNoise, BigDecimal positiveFloor, String algorithmVersion) {
    public ClubEquityProfile {
        maximumNoise = SafeCompanyProfile.requireRange(maximumNoise, BigDecimal.ZERO, new BigDecimal("0.10"),
                "maximumNoise");
        if (positiveFloor == null || positiveFloor.signum() <= 0) {
            throw new IllegalArgumentException("positiveFloor must be positive");
        }
        algorithmVersion = SafeCompanyProfile.requireText(algorithmVersion, "algorithmVersion");
    }
}
