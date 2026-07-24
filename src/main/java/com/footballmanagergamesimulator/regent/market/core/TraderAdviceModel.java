package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Imperfect signal interpretation. Its deterministic noise is domain-separated from quote randomness,
 * and all inputs represent the present or history, so it cannot observe a future generated return.
 */
public final class TraderAdviceModel {
    private static final BigDecimal HOLD_BAND = new BigDecimal("0.0025");

    public TraderAdvice recommend(MarketQuoteKey key, TraderAdviser adviser, AdviserSignal signal) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(adviser, "adviser");
        Objects.requireNonNull(signal, "signal");
        double rawNoise = DeterministicMarketRandom.unit(key.saveSeed(), signal.instrumentId(), key.day(),
                key.saveVersion() + ':' + adviser.modelVersion(), "adviser-observation") * 2.0d - 1.0d;
        BigDecimal errorAmplitude = BigDecimal.valueOf(100 - adviser.skill()).movePointLeft(4);
        BigDecimal interpreted = signal.trailingReturn().add(BigDecimal.valueOf(rawNoise).multiply(errorAmplitude));
        AdviceAction action = interpreted.compareTo(HOLD_BAND) > 0 ? AdviceAction.BUY
                : interpreted.compareTo(HOLD_BAND.negate()) < 0 ? AdviceAction.SELL : AdviceAction.HOLD;
        BigDecimal confidence = BigDecimal.valueOf(adviser.skill()).movePointLeft(2)
                .multiply(BigDecimal.ONE.subtract(signal.observedVolatility().min(BigDecimal.ONE)))
                .max(new BigDecimal("0.05")).min(new BigDecimal("0.99")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal classRisk = switch (signal.riskClass()) {
            case SAFE_COMPANY -> new BigDecimal("0.20");
            case SPECULATIVE -> new BigDecimal("0.90");
            case CLUB_EQUITY -> new BigDecimal("0.50");
        };
        BigDecimal risk = classRisk.add(signal.observedVolatility().min(BigDecimal.ONE).multiply(new BigDecimal("0.10")))
                .min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        int horizon = switch (signal.riskClass()) {
            case SAFE_COMPANY -> 30;
            case SPECULATIVE -> 7;
            case CLUB_EQUITY -> 21;
        };
        String explanation = action + " from observed trailing momentum with skill-adjusted uncertainty; no future quote is used.";
        return new TraderAdvice(signal.instrumentId(), action, horizon, confidence, risk, explanation, adviser.modelVersion());
    }
}
