package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.service.ClubNegotiation.Answer;
import com.footballmanagergamesimulator.service.ClubNegotiation.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubNegotiationTest {

    @Test
    @DisplayName("""
            GIVEN a club asking 1000
            WHEN it sets its hidden minimum, many times, and its patience
            THEN the minimum is 700–1000 (the ask minus 0–30%) and spreads over that range;
             patience is 3–10 rounds and fixed per club
            """)
    void minimumAndPatience() {
        Random rng = new Random(3);
        long low = Long.MAX_VALUE, high = 0;
        for (int i = 0; i < 1_000; i++) {
            long minimum = ClubNegotiation.minimum(1_000, rng);
            low = Math.min(low, minimum);
            high = Math.max(high, minimum);
        }
        assertTrue(low >= 700 && low < 710, "low " + low);
        assertTrue(high <= 1_000 && high > 990, "high " + high);
        for (long club = 1; club < 200; club++) {
            int rounds = ClubNegotiation.roundsOf(club);
            assertTrue(rounds >= 3 && rounds <= 10);
            assertEquals(rounds, ClubNegotiation.roundsOf(club));
        }
    }

    @Test
    @DisplayName("""
            GIVEN a club asking 1000 (hidden minimum 900) with 4 rounds of patience
            WHEN I offer 800 in each round
            THEN each time it comes down a random 25–75% of the way to its minimum (my offer is under it),
             never under 900, and in its last round its ask becomes a take-it-or-leave-it offer
            """)
    void clubComesDownByARandomShareAndEndsWithAnUltimatum() {
        Random rng = new Random(11);
        long ask = 1_000;
        for (int round = 1; round <= 3; round++) {
            Answer answer = ClubNegotiation.answer(800, ask, 900, 0, round, 4, rng);
            long gap = ask - 900;
            assertTrue(answer.ask() >= ask - Math.ceil(gap * 0.75) && answer.ask() <= ask - Math.ceil(gap * 0.25),
                    "round " + round + ": " + ask + " -> " + answer.ask());
            assertTrue(answer.ask() >= 900);
            assertEquals(round == 3 ? Outcome.FINAL_OFFER : Outcome.TURNED_DOWN, answer.outcome());
            ask = answer.ask();
        }
    }

    @Test
    @DisplayName("""
            GIVEN a club asking 1000 (minimum 900)
            WHEN many clubs answer the same offer of 900 in round 1
            THEN their new asks spread across the 25–75% range instead of all landing on the halfway point
            """)
    void concessionIsRandomNotHalfway() {
        Random rng = new Random(2);
        java.util.Set<Long> asks = new java.util.TreeSet<>();
        for (int i = 0; i < 200; i++) asks.add(ClubNegotiation.answer(900, 1_000, 900, 0, 1, 10, rng).ask());
        assertTrue(asks.size() > 20, "asks " + asks);
        assertTrue(asks.stream().allMatch(a -> a >= 925 && a <= 975), "asks " + asks);
    }

    @Test
    @DisplayName("""
            GIVEN a club asking 1000 with a hidden minimum of 900
            WHEN I offer 720 or 719
            THEN 720 (80% of its minimum) is still discussed; 719 is far too weak and the club leaves the table
            """)
    void farTooWeakOfferEndsTheTalks() {
        assertEquals(Outcome.TURNED_DOWN, ClubNegotiation.answer(720, 1_000, 900, 0, 1, 4, new Random(1)).outcome());
        assertEquals(Outcome.CLUB_WALKED_AWAY, ClubNegotiation.answer(719, 1_000, 900, 0, 1, 4, new Random(1)).outcome());
    }

    @Test
    @DisplayName("""
            GIVEN a club asking 1000 (minimum 900) that would talk forever
            WHEN I keep offering 900
            THEN it ends up accepting 900, its minimum
            """)
    void endlessTalksReachTheMinimum() {
        long ask = 1_000;
        Answer answer = null;
        for (int round = 2; round < 100; round++) {
            answer = ClubNegotiation.answer(900, ask, 900, 0, round, 1_000, new Random(round));
            if (answer.outcome() == Outcome.FEE_AGREED) break;
            ask = answer.ask();
        }
        assertEquals(new Answer(Outcome.FEE_AGREED, 900), answer);
    }

    @Test
    @DisplayName("""
            GIVEN a club asking 1000 (minimum 700) for a player with a release clause of 600
            WHEN I offer the ask, or the clause, or just under the clause
            THEN the ask and the clause agree the fee; just under the clause does not
            """)
    void askOrReleaseClauseAgreeTheFee() {
        assertEquals(Outcome.FEE_AGREED, ClubNegotiation.answer(1_000, 1_000, 700, 0, 1, 4, new Random(1)).outcome());
        assertEquals(Outcome.FEE_AGREED, ClubNegotiation.answer(600, 1_000, 700, 600, 1, 4, new Random(1)).outcome());
        assertEquals(Outcome.TURNED_DOWN, ClubNegotiation.answer(599, 1_000, 700, 600, 1, 4, new Random(1)).outcome());
    }

    @Test
    @DisplayName("""
            GIVEN failed talks
            WHEN the cool-down is drawn many times
            THEN it is always 3–10 days and takes every value
            """)
    void cooldownIsThreeToTenDays() {
        Random rng = new Random(5);
        java.util.Set<Integer> seen = new java.util.TreeSet<>();
        for (int i = 0; i < 500; i++) seen.add(ClubNegotiation.cooldownDays(rng));
        assertEquals(java.util.Set.of(3, 4, 5, 6, 7, 8, 9, 10), seen);
    }
}
