package com.footballmanagergamesimulator.matchplan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure mapping from a resolved tie to plan inputs — the piece the hot loop feeds
 * into {@code buildAndPersist}. Mirrors the values {@code KnockoutMatchResolution}
 * exposes (per-team ET goals + shootout, null when not played).
 */
class KnockoutPlanSplitTest {

    @Test
    void regularOnly_hasNoExtraTimeOrShootout() {
        KnockoutPlanSplit s = KnockoutPlanSplit.regularOnly(3, 2);
        assertEquals(3, s.score90Home());
        assertEquals(2, s.score90Away());
        assertEquals(-1, s.etHome());
        assertEquals(-1, s.etAway());
        assertEquals(-1, s.shootoutHome());
        assertEquals(-1, s.shootoutAway());
    }

    @Test
    void extraTimeWinner_carriesEtGoals_noShootout() {
        // 1-1 at 90, home wins with a 1-0 extra time, no penalties.
        KnockoutPlanSplit s = KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null);
        assertEquals(1, s.score90Home());
        assertEquals(1, s.etHome());
        assertEquals(0, s.etAway());
        assertEquals(-1, s.shootoutHome());
        assertEquals(-1, s.shootoutAway());
    }

    @Test
    void penaltiesAfterLevelExtraTime_carriesEtGoalsAndShootout() {
        // 1-1 at 90, 1-1 in ET, decided 5-4 on penalties.
        KnockoutPlanSplit s = KnockoutPlanSplit.knockout(1, 1, 1, 1, 5, 4);
        assertEquals(1, s.etHome());
        assertEquals(1, s.etAway());
        assertEquals(5, s.shootoutHome());
        assertEquals(4, s.shootoutAway());
    }

    @Test
    void noExtraTimePlayed_nullBecomesSentinel() {
        // Two-leg decided on aggregate (or single-leg in normal time): no ET.
        KnockoutPlanSplit s = KnockoutPlanSplit.knockout(2, 1, null, null, null, null);
        assertEquals(-1, s.etHome());
        assertEquals(-1, s.etAway());
        assertEquals(-1, s.shootoutHome());
        assertEquals(-1, s.shootoutAway());
    }

    @Test
    void orientation_homeAndAwayEtKeptDistinct() {
        // Only the away side scores in extra time.
        KnockoutPlanSplit s = KnockoutPlanSplit.knockout(0, 0, 0, 1, null, null);
        assertEquals(0, s.etHome());
        assertEquals(1, s.etAway());
    }
}
