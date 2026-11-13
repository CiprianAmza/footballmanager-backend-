package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.service.TransferPoints.Offer;
import com.footballmanagergamesimulator.service.TransferPoints.Situation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferPointsTest {

    private static final long REAL_PRICE = 1_000_000;

    @Test
    @DisplayName("""
            GIVEN 400,000 now plus 2 instalments of 300,000
            WHEN a club in trouble and a stable club value it
            THEN the trouble club counts each instalment at 50% (700,000), the stable one at 90% (940,000)
            """)
    void instalmentsAreWorthLessToAClubInTrouble() {
        Offer offer = new Offer(400_000, 2, 300_000, 0, 0);
        assertEquals(700_000, TransferPoints.points(offer, REAL_PRICE, Situation.TROUBLED));
        assertEquals(940_000, TransferPoints.points(offer, REAL_PRICE, Situation.STABLE));
    }

    @Test
    @DisplayName("""
            GIVEN 800,000 now with a 20% sell-on for a player really worth 1,000,000
            WHEN each kind of club values it
            THEN the sell-on adds 10%, 30% or 20% of 200,000: 820,000 / 860,000 / 840,000
            """)
    void sellOnIsWorthMostToAStableClub() {
        Offer offer = new Offer(800_000, 0, 0, 20, 0);
        assertEquals(820_000, TransferPoints.points(offer, REAL_PRICE, Situation.TROUBLED));
        assertEquals(860_000, TransferPoints.points(offer, REAL_PRICE, Situation.STABLE));
        assertEquals(840_000, TransferPoints.points(offer, REAL_PRICE, Situation.LOSING_STARTER));
    }

    @Test
    @DisplayName("""
            GIVEN 800,000 now with a buy-back clause
            WHEN each kind of club values it
            THEN it adds nothing in trouble, 5% of the real price when stable, 15% when losing a starter
            """)
    void buyBackIsWorthMostToAClubLosingAStarter() {
        Offer offer = new Offer(800_000, 0, 0, 0, 1_500_000);
        assertEquals(800_000, TransferPoints.points(offer, REAL_PRICE, Situation.TROUBLED));
        assertEquals(850_000, TransferPoints.points(offer, REAL_PRICE, Situation.STABLE));
        assertEquals(950_000, TransferPoints.points(offer, REAL_PRICE, Situation.LOSING_STARTER));
    }

    @Test
    @DisplayName("""
            GIVEN clubs in different situations
            WHEN their situation is read and offers are checked
            THEN trouble wins over losing a starter (top 11); 0–3 instalments and a 0–50% sell-on are valid
            """)
    void situationAndValidity() {
        assertEquals(Situation.TROUBLED, TransferPoints.situation(true, 1));
        assertEquals(Situation.LOSING_STARTER, TransferPoints.situation(false, 11));
        assertEquals(Situation.STABLE, TransferPoints.situation(false, 12));
        assertTrue(new Offer(0, 3, 100, 50, 0).valid());
        assertFalse(new Offer(100, 4, 100, 0, 0).valid());
        assertFalse(new Offer(100, 0, 0, 51, 0).valid());
        assertFalse(new Offer(100, 2, 0, 0, 0).valid());
        assertEquals(700, new Offer(100, 3, 200, 0, 0).total());
    }
}
