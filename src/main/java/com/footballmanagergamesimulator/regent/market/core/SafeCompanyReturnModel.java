package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;

/** Pure daily return model. Positive bias is probabilistic, never a monotonic-price promise. */
public final class SafeCompanyReturnModel {
    public DailyReturn quote(MarketQuoteKey key, SafeCompanyProfile profile) {
        BigDecimal magnitude = profile.maximumDailyMagnitude()
                .multiply(BigDecimal.valueOf(DeterministicMarketRandom.unit(key.saveSeed(), key.instrumentId(), key.day(),
                        key.saveVersion() + ':' + profile.algorithmVersion(), "safe-magnitude")));
        boolean positive = DeterministicMarketRandom.unit(key.saveSeed(), key.instrumentId(), key.day(),
                key.saveVersion() + ':' + profile.algorithmVersion(), "safe-direction")
                < profile.positiveProbability().doubleValue();
        return new DailyReturn(positive ? magnitude : magnitude.negate(), profile.algorithmVersion());
    }
}
