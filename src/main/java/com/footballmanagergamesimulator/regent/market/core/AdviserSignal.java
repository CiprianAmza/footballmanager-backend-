package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.util.Objects;

/** Only already-observed information is admissible; there is deliberately no future return field. */
public record AdviserSignal(String instrumentId, MarketRiskClass riskClass, BigDecimal trailingReturn,
                            BigDecimal observedVolatility) {
    public AdviserSignal {
        instrumentId = SafeCompanyProfile.requireText(instrumentId, "instrumentId");
        riskClass = Objects.requireNonNull(riskClass, "riskClass");
        trailingReturn = Objects.requireNonNull(trailingReturn, "trailingReturn");
        observedVolatility = Objects.requireNonNull(observedVolatility, "observedVolatility");
        if (observedVolatility.signum() < 0) throw new IllegalArgumentException("observedVolatility must not be negative");
    }
}
