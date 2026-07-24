package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.util.Objects;

/** BigDecimal arithmetic deliberately avoids long overflow at extreme legitimate prices. */
public final class SpeculativeQuoteModel {
    private static final BigDecimal HALF = new BigDecimal("0.50");

    public SpeculativeQuote quote(MarketQuoteKey key, BigDecimal openingPrice, SpeculativeProfile profile) {
        Objects.requireNonNull(openingPrice, "openingPrice");
        if (openingPrice.signum() <= 0) throw new IllegalArgumentException("openingPrice must be positive");
        double unit = DeterministicMarketRandom.unit(key.saveSeed(), key.instrumentId(), key.day(),
                key.saveVersion() + ':' + profile.algorithmVersion(), "speculative-return");
        DailyReturn dailyReturn = new DailyReturn(BigDecimal.valueOf(unit).multiply(BigDecimal.ONE).subtract(HALF),
                profile.algorithmVersion());
        BigDecimal closing = openingPrice.multiply(BigDecimal.ONE.add(dailyReturn.value())).max(profile.positiveFloor());
        return new SpeculativeQuote(dailyReturn, closing);
    }
}
