package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Accepts a valuation snapshot rather than calling ClubValuationService: this preserves the pure boundary.
 * The reference price is valuation / finite supply; noise may only move it within the configured band.
 */
public final class ClubEquityQuoteModel {
    public ClubEquityQuote quote(MarketQuoteKey key, BigDecimal valuation, long issuedSupply, ClubEquityProfile profile) {
        Objects.requireNonNull(valuation, "valuation");
        if (valuation.signum() < 0) throw new IllegalArgumentException("valuation must not be negative");
        if (issuedSupply <= 0) throw new IllegalArgumentException("issuedSupply must be positive");
        BigDecimal reference = valuation.divide(BigDecimal.valueOf(issuedSupply), MathContext.DECIMAL128);
        double unit = DeterministicMarketRandom.unit(key.saveSeed(), key.instrumentId(), key.day(),
                key.saveVersion() + ':' + profile.algorithmVersion(), "club-equity-noise");
        BigDecimal noise = BigDecimal.valueOf(unit * 2.0d - 1.0d).multiply(profile.maximumNoise());
        BigDecimal quote = reference.multiply(BigDecimal.ONE.add(noise)).max(profile.positiveFloor());
        return new ClubEquityQuote(reference, noise, quote, profile.algorithmVersion());
    }
}
