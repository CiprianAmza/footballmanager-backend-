package com.footballmanagergamesimulator.service.press;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRumourArticleTest {

    @Test
    @DisplayName("""
            GIVEN offers that are pending, countered and under negotiation
            WHEN the rumour is classified
            THEN only the answered ones count as advanced talks: EARLY, STRONG, STRONG
            """)
    void offerStatusSetsTheStage() {
        assertFalse(TransferRumourArticle.advanced("pending"));
        assertTrue(TransferRumourArticle.advanced("counter"));
        assertTrue(TransferRumourArticle.advanced("NEGOTIATING"));
        assertEquals("EARLY", TransferRumourArticle.bucket(false));
        assertEquals("STRONG", TransferRumourArticle.bucket(true));
    }

    @Test
    @DisplayName("""
            GIVEN reporters of reliability 20, 50 and 90 and a real fee of 12,000,000
            WHEN each writes 500 rumours
            THEN the credibility shown stays within 5 of his reliability (10 higher for advanced talks), never
             above 95 or below 5, and the fee he quotes is off by at most 40%, 25% and 5% (plus rounding)
            """)
    void reliabilitySetsCredibilityAndFee() {
        Random rng = new Random(11);
        long realFee = 12_000_000;
        int[] reliabilities = {20, 50, 90};
        double[] maxErrors = {0.40, 0.25, 0.05};
        for (int r = 0; r < reliabilities.length; r++) {
            for (int i = 0; i < 500; i++) {
                int early = TransferRumourArticle.credibility(reliabilities[r], false, rng);
                int advanced = TransferRumourArticle.credibility(reliabilities[r], true, rng);
                assertTrue(Math.abs(early - reliabilities[r]) <= 5, "early " + early);
                assertTrue(advanced <= 95 && advanced >= Math.min(95, reliabilities[r] + 5), "advanced " + advanced);

                long quoted = TransferRumourArticle.reportedFee(realFee, reliabilities[r], rng);
                double error = Math.abs(quoted - realFee) / (double) realFee;
                assertTrue(error <= maxErrors[r] + 0.03, reliabilities[r] + " quoted " + quoted);
            }
        }
        assertEquals(95, TransferRumourArticle.credibility(100, true, new Random(1)));
        assertEquals(5, TransferRumourArticle.credibility(0, false, new Random(3)));
        assertEquals(0, TransferRumourArticle.reportedFee(0, 50, rng));
    }

    @Test
    @DisplayName("""
            GIVEN the amounts 12,437,000, 950,000, 1,234 and 800
            WHEN they are rounded the way fees are quoted and printed
            THEN they read 12,500,000, 950,000, 1,250 and 800, shown as "€12,500,000"; credibility 85, 65, 45 and 20
             is labelled rarely wrong, usually well informed, mixed record and often wide of the mark
            """)
    void feesAreRoundedAndLabelled() {
        assertEquals(12_500_000, TransferRumourArticle.rounded(12_437_000));
        assertEquals(950_000, TransferRumourArticle.rounded(950_000));
        assertEquals(1_250, TransferRumourArticle.rounded(1_234));
        assertEquals(800, TransferRumourArticle.rounded(800));
        assertEquals("€12,500,000", TransferRumourArticle.money(12_500_000));
        assertEquals("rarely wrong", TransferRumourArticle.label(85));
        assertEquals("usually well informed", TransferRumourArticle.label(65));
        assertEquals("mixed record", TransferRumourArticle.label(45));
        assertEquals("often wide of the mark", TransferRumourArticle.label(20));
    }
}
