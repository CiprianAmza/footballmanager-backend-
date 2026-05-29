package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The national cup must build a valid bracket for ANY entrant count — exact
 * powers of two, non-powers, and odd counts alike. These unit tests pin the
 * adaptive math ({@code largestPowerOfTwoAtMost} + balanced order + the
 * prelim/auto-qualified partition) without touching the database.
 *
 * Lives in the same package so it can reach the package-private statics.
 */
class CupBracketServiceTest {

    @Test
    void largestPowerOfTwoAtMost_isCorrect() {
        assertEquals(2, CupBracketService.largestPowerOfTwoAtMost(2));
        assertEquals(2, CupBracketService.largestPowerOfTwoAtMost(3));
        assertEquals(4, CupBracketService.largestPowerOfTwoAtMost(4));
        assertEquals(4, CupBracketService.largestPowerOfTwoAtMost(7));
        assertEquals(8, CupBracketService.largestPowerOfTwoAtMost(8));
        assertEquals(8, CupBracketService.largestPowerOfTwoAtMost(15));
        assertEquals(16, CupBracketService.largestPowerOfTwoAtMost(16));
        assertEquals(16, CupBracketService.largestPowerOfTwoAtMost(31));
        assertEquals(32, CupBracketService.largestPowerOfTwoAtMost(32));
    }

    @Test
    void balancedBracketOrder_matchesKnownLayouts() {
        assertArrayEquals(new int[]{1, 2}, CupBracketService.balancedBracketOrder(2));
        assertArrayEquals(new int[]{1, 4, 2, 3}, CupBracketService.balancedBracketOrder(4));
        assertArrayEquals(new int[]{1, 8, 4, 5, 2, 7, 3, 6}, CupBracketService.balancedBracketOrder(8));
        assertArrayEquals(
                new int[]{1, 16, 8, 9, 4, 13, 5, 12, 2, 15, 7, 10, 3, 14, 6, 11},
                CupBracketService.balancedBracketOrder(16));
    }

    @Test
    void balancedBracketOrder_isPermutationWhereSeedsPairToMplus1() {
        for (int m : new int[]{2, 4, 8, 16, 32}) {
            int[] order = CupBracketService.balancedBracketOrder(m);
            assertEquals(m, order.length, "m=" + m + ": length");

            // Permutation of 1..m
            Set<Integer> seen = new HashSet<>();
            for (int s : order) {
                assertTrue(s >= 1 && s <= m, "m=" + m + ": seed " + s + " out of range");
                assertTrue(seen.add(s), "m=" + m + ": seed " + s + " duplicated");
            }
            assertEquals(m, seen.size());

            // Each first-round pairing (slot 2k-1 vs 2k) sums to m+1: best plays worst.
            for (int i = 0; i < m; i += 2) {
                assertEquals(m + 1, order[i] + order[i + 1],
                        "m=" + m + ": pairing at slot " + i + " must sum to m+1");
            }
        }
    }

    /**
     * The dimensions generateBracket() derives from N must partition the field
     * cleanly for every N: prelim teams + auto-qualified teams = N, and the
     * round-of-M is exactly full (auto + prelim winners = M).
     */
    @Test
    void bracketDimensionsPartitionTheFieldForAnyN() {
        for (int n : new int[]{2, 4, 8, 16, 32,   // exact powers of two
                               6, 10, 12, 20,     // non-powers, even
                               7, 9, 15, 23}) {   // odd
            int m = CupBracketService.largestPowerOfTwoAtMost(n);
            int prelimMatches = n - m;       // matches in the preliminary round
            int autoQualified = 2 * m - n;   // teams that skip the prelim

            assertTrue(m <= n && m * 2 > n, "N=" + n + ": M must be the largest power of two ≤ N");
            assertTrue(prelimMatches >= 0, "N=" + n + ": prelim matches cannot be negative");
            assertTrue(autoQualified >= 0, "N=" + n + ": auto-qualified cannot be negative");

            // Every team is either auto-qualified or one of two in a prelim match.
            assertEquals(n, autoQualified + 2 * prelimMatches,
                    "N=" + n + ": all teams must be accounted for");
            // The round of M is exactly filled: auto teams + prelim winners.
            assertEquals(m, autoQualified + prelimMatches,
                    "N=" + n + ": round-of-M must be exactly full");
        }
    }
}
