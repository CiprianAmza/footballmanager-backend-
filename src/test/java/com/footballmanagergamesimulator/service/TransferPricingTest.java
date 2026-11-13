package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.service.TransferPricing.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferPricingTest {

    @Test
    @DisplayName("""
            GIVEN a player worth 1,000,000 on the market
            WHEN his contract has 3, 2, 1 or 0 years left (rotation player, happy, stable club, no other offers)
            THEN his real price is 1,000,000, 900,000, 750,000 and 500,000
            """)
    void lessContractLeftMeansCheaper() {
        assertEquals(1_000_000, TransferPricing.realPrice(new Context(1_000_000, 3, false, 15, false, 0, 0)));
        assertEquals(900_000, TransferPricing.realPrice(new Context(1_000_000, 2, false, 15, false, 0, 0)));
        assertEquals(750_000, TransferPricing.realPrice(new Context(1_000_000, 1, false, 15, false, 0, 0)));
        assertEquals(500_000, TransferPricing.realPrice(new Context(1_000_000, 0, false, 15, false, 0, 0)));
    }

    @Test
    @DisplayName("""
            GIVEN a player worth 1,000,000 with 3+ years left
            WHEN he is a starter, a reserve, wants to leave, or his club is in trouble
            THEN the price is ×1.2, ×0.8, ×0.7 and ×0.8 respectively
            """)
    void importanceWishAndClubSituationMoveThePrice() {
        assertEquals(1_200_000, TransferPricing.realPrice(new Context(1_000_000, 4, false, 11, false, 0, 0)));
        assertEquals(800_000, TransferPricing.realPrice(new Context(1_000_000, 4, false, 19, false, 0, 0)));
        assertEquals(700_000, TransferPricing.realPrice(new Context(1_000_000, 4, true, 15, false, 0, 0)));
        assertEquals(800_000, TransferPricing.realPrice(new Context(1_000_000, 4, false, 15, true, 0, 0)));
    }

    @Test
    @DisplayName("""
            GIVEN a player worth 1,000,000
            WHEN 2 or 5 other clubs are after him, or he has a release clause of 600,000
            THEN the price rises 10% per other club up to +30%, and never goes above the clause
            """)
    void otherClubsRaiseThePriceAndTheClauseCapsIt() {
        assertEquals(1_200_000, TransferPricing.realPrice(new Context(1_000_000, 3, false, 15, false, 2, 0)));
        assertEquals(1_300_000, TransferPricing.realPrice(new Context(1_000_000, 3, false, 15, false, 5, 0)));
        assertEquals(600_000, TransferPricing.realPrice(new Context(1_000_000, 3, false, 15, false, 0, 600_000)));
    }

    @Test
    @DisplayName("""
            GIVEN a real price of 1,000,000
            WHEN clubs set what they want, many times
            THEN it always lies between 650,000 and 1,400,000, spreads over that range, and respects a clause
            """)
    void clubWantsBetweenMinus35AndPlus40Percent() {
        Random rng = new Random(7);
        long min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < 2_000; i++) {
            long want = TransferPricing.clubWants(1_000_000, 0, rng);
            min = Math.min(min, want);
            max = Math.max(max, want);
        }
        assertTrue(min >= 650_000 && min < 680_000, "min " + min);
        assertTrue(max <= 1_400_000 && max > 1_370_000, "max " + max);
        assertTrue(TransferPricing.clubWants(1_000_000, 700_000, rng) <= 700_000);
    }
}
