package com.footballmanagergamesimulator.regent.market.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MarketCoreTest {
    private final SafeCompanyReturnModel safeModel = new SafeCompanyReturnModel();
    private final SafeCompanyProfile safeProfile = new SafeCompanyProfile(new BigDecimal("0.01"),
            new BigDecimal("0.65"), "safe-v1");

    @Test
    void safeCompanyIsDeterministicBoundedAndNotMonotonic() {
        MarketQuoteKey key = new MarketQuoteKey(18L, "safe-1", 400L, "save-v1");
        assertEquals(safeModel.quote(key, safeProfile), safeModel.quote(key, safeProfile));
        boolean sawNegative = false;
        boolean sawPositive = false;
        for (int day = 0; day < 1_000; day++) {
            BigDecimal value = safeModel.quote(new MarketQuoteKey(18L, "safe-1", day, "save-v1"), safeProfile).value();
            assertTrue(value.abs().compareTo(new BigDecimal("0.01")) <= 0);
            sawNegative |= value.signum() < 0;
            sawPositive |= value.signum() > 0;
        }
        assertTrue(sawNegative);
        assertTrue(sawPositive);
    }

    @Test
    void safeCompanyPositiveBiasIsVisibleAcrossIndependentDays() {
        int positive = 0;
        for (int day = 0; day < 10_000; day++) {
            if (safeModel.quote(new MarketQuoteKey(991L, "safe-bias", day, "save-v1"), safeProfile).value().signum() >= 0) positive++;
        }
        assertTrue(positive > 6_000, "configured 65% directional bias should dominate a representative sample");
    }

    @Test
    void safeCompanyRequiresStrictPositiveBias() {
        assertThrows(IllegalArgumentException.class, () -> new SafeCompanyProfile(BigDecimal.ZERO, BigDecimal.ZERO, "safe-v1"));
        assertThrows(IllegalArgumentException.class, () -> new SafeCompanyProfile(BigDecimal.ZERO, new BigDecimal("0.50"), "safe-v1"));
        assertThrows(IllegalArgumentException.class, () -> new SafeCompanyProfile(BigDecimal.ZERO, new BigDecimal("1.01"), "safe-v1"));
        assertDoesNotThrow(() -> new SafeCompanyProfile(BigDecimal.ZERO, new BigDecimal("0.5001"), "safe-v1"));
    }

    @Test
    void speculativeReturnIsHardBoundedAndPriceFloorSurvives() {
        SpeculativeQuoteModel model = new SpeculativeQuoteModel();
        SpeculativeProfile profile = new SpeculativeProfile(new BigDecimal("0.75"), "spec-v1");
        boolean floorApplied = false;
        for (int day = 0; day < 2_000; day++) {
            SpeculativeQuote quote = model.quote(new MarketQuoteKey(7L, "spec", day, "save-v1"), new BigDecimal("1.00"), profile);
            assertTrue(quote.dailyReturn().value().compareTo(new BigDecimal("-0.50")) >= 0);
            assertTrue(quote.dailyReturn().value().compareTo(new BigDecimal("0.50")) <= 0);
            BigDecimal realizedReturn = quote.closingPrice().divide(new BigDecimal("1.00")).subtract(BigDecimal.ONE);
            assertTrue(realizedReturn.compareTo(new BigDecimal("-0.50")) >= 0);
            assertTrue(realizedReturn.compareTo(new BigDecimal("0.50")) <= 0);
            assertTrue(quote.closingPrice().compareTo(new BigDecimal("0.75")) >= 0);
            floorApplied |= quote.closingPrice().compareTo(new BigDecimal("0.75")) == 0;
        }
        assertTrue(floorApplied, "the valid floor path must retain the same hard realized-return bound");
        assertThrows(IllegalArgumentException.class, () -> model.quote(new MarketQuoteKey(7L, "spec", 0L, "save-v1"),
                new BigDecimal("0.50"), profile));
    }

    @Test
    void speculativeArithmeticDoesNotOverflowAtLongLimit() {
        SpeculativeQuote quote = new SpeculativeQuoteModel().quote(new MarketQuoteKey(4L, "spec", 1L, "save"),
                new BigDecimal(Long.MAX_VALUE), new SpeculativeProfile(BigDecimal.ONE, "spec-v1"));
        assertTrue(quote.closingPrice().signum() > 0);
        assertTrue(quote.closingPrice().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) != 0
                || quote.dailyReturn().value().signum() == 0);
    }

    @Test
    void clubEquityReferenceUsesValuationAndFiniteSupplyOnly() {
        ClubEquityQuote quote = new ClubEquityQuoteModel().quote(new MarketQuoteKey(1L, "club-8", 2L, "save"),
                new BigDecimal("1000"), 40L, new ClubEquityProfile(new BigDecimal("0.02"), new BigDecimal("0.01"), "club-v1"));
        assertEquals(0, quote.referencePrice().compareTo(new BigDecimal("25")));
        assertTrue(quote.marketNoise().abs().compareTo(new BigDecimal("0.02")) <= 0);
        assertTrue(quote.quotedPrice().signum() > 0);
        assertThrows(IllegalArgumentException.class, () -> new ClubEquityQuoteModel().quote(
                new MarketQuoteKey(1L, "club-8", 2L, "save"), BigDecimal.TEN, 0L,
                new ClubEquityProfile(BigDecimal.ZERO, BigDecimal.ONE, "club-v1")));
    }

    @Test
    void adviserIsDeterministicSkillSensitiveAndHasContractTerms() {
        TraderAdviser expert = adviser("expert", 90);
        TraderAdviser novice = adviser("novice", 20);
        AdviserSignal signal = new AdviserSignal("safe-1", MarketRiskClass.SAFE_COMPANY,
                new BigDecimal("0.006"), new BigDecimal("0.10"));
        TraderAdviceModel model = new TraderAdviceModel();
        TraderAdvice advice = model.recommend(new MarketQuoteKey(5L, "safe-1", 5L, "save-v1"), expert, signal);
        assertEquals(advice, model.recommend(new MarketQuoteKey(5L, "safe-1", 5L, "save-v1"), expert, signal));
        assertTrue(advice.confidence().compareTo(model.recommend(new MarketQuoteKey(5L, "safe-1", 5L, "save-v1"), novice, signal).confidence()) > 0);
        assertTrue(expert.activeOn(LocalDate.of(2030, 1, 1)));
        assertFalse(expert.activeOn(LocalDate.of(2031, 1, 1)));
    }

    @Test
    void adviserInputCannotContainFutureReturnAndAdviceCannotExecuteTrade() {
        assertFalse(Arrays.stream(AdviserSignal.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("future")));
        assertEquals(0, TraderAdvice.class.getDeclaredMethods().length - TraderAdvice.class.getRecordComponents().length - 3,
                "advice remains an immutable record without an execution API");
    }

    @Test
    void adviserRequiresTheQuoteAndSignalToIdentifyTheSameInstrument() {
        AdviserSignal signal = new AdviserSignal("instrument-a", MarketRiskClass.SAFE_COMPANY, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> new TraderAdviceModel().recommend(
                new MarketQuoteKey(1L, "instrument-b", 1L, "save-v1"), adviser("expert", 90), signal));
    }

    private TraderAdviser adviser(String id, int skill) {
        return new TraderAdviser(id, skill, 70, new BigDecimal("250"), LocalDate.of(2030, 1, 1),
                LocalDate.of(2030, 12, 31), "adviser-v1");
    }
}
