package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.PlayerMatchState;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure helpers exposed by {@link LiveMatchSimulationService}.
 * These cover the stateless logic added in the pace / red-card / split-tick
 * refactor; the full engine is exercised end-to-end through the live-match
 * integration paths (not covered here).
 */
class LiveMatchSimulationServiceTest {

    /** Build a fresh service with the production-default engine config injected. */
    private static LiveMatchSimulationService newService() {
        LiveMatchSimulationService svc = new LiveMatchSimulationService();
        svc.engineConfig = new MatchEngineConfig();
        return svc;
    }

    // ---------------- man-advantage multiplier ----------------

    @Test
    void manAdvantageMultiplier_eleven_isNeutral() {
        assertEquals(1.0, newService().manAdvantageAttackMultiplier(11));
    }

    @Test
    void manAdvantageMultiplier_oneMoreThanEleven_stillNeutral() {
        // Treat 12+ defensively the same as 11 — no negative penalty.
        assertEquals(1.0, newService().manAdvantageAttackMultiplier(15));
    }

    @Test
    void manAdvantageMultiplier_tenMen_seventyPercent() {
        assertEquals(0.7, newService().manAdvantageAttackMultiplier(10));
    }

    @Test
    void manAdvantageMultiplier_nineMen_fiftyPercent() {
        assertEquals(0.5, newService().manAdvantageAttackMultiplier(9));
    }

    @Test
    void manAdvantageMultiplier_eightOrFewer_floor() {
        LiveMatchSimulationService svc = newService();
        assertEquals(0.35, svc.manAdvantageAttackMultiplier(8));
        assertEquals(0.35, svc.manAdvantageAttackMultiplier(5));
        assertEquals(0.35, svc.manAdvantageAttackMultiplier(0));
    }

    // ---------------- tempo multiplier ----------------

    @Test
    void tempoMultiplier_standard_isOne() {
        assertEquals(1.0, newService().tempoMultiplier("Standard"));
    }

    @Test
    void tempoMultiplier_high_isBoost() {
        assertEquals(1.25, newService().tempoMultiplier("High"));
    }

    @Test
    void tempoMultiplier_low_isDiscount() {
        assertEquals(0.85, newService().tempoMultiplier("Low"));
    }

    @Test
    void tempoMultiplier_caseInsensitive() {
        LiveMatchSimulationService svc = newService();
        assertEquals(1.25, svc.tempoMultiplier("high"));
        assertEquals(0.85, svc.tempoMultiplier("LOW"));
    }

    @Test
    void tempoMultiplier_nullOrUnknown_defaultsToStandard() {
        LiveMatchSimulationService svc = newService();
        assertEquals(1.0, svc.tempoMultiplier(null));
        assertEquals(1.0, svc.tempoMultiplier("Bonkers"));
    }

    // ---------------- pickFouler (pace-weighted) ----------------

    @Test
    void pickFouler_emptyDefenders_returnsNull() {
        LiveMatchSimulationService svc = newService();
        assertNull(svc.pickFouler(List.of(), new HashMap<>(), new Random(0)));
    }

    @Test
    void pickFouler_singleDefender_returnsThatDefender() {
        LiveMatchSimulationService svc = newService();
        Human only = human(1L, "Only DC", "DC", 60);
        Human picked = svc.pickFouler(List.of(only), new HashMap<>(), new Random(0));
        assertSame(only, picked);
    }

    @Test
    void pickFouler_paceWeight_slowerDefenderFoulsMoreOften() {
        // Two defenders, identical except for pace. Over many rolls the slow
        // one (pace=2) should be picked notably more often than the quick one
        // (pace=20). Threshold deliberately loose to avoid flakes.
        LiveMatchSimulationService svc = newService();
        Human slow = human(1L, "Slowpoke", "DC", 60);
        Human fast = human(2L, "Roadrunner", "DC", 60);
        Map<Long, PlayerMatchState> states = new HashMap<>();
        states.put(1L, state(1L, "DC", 2));
        states.put(2L, state(2L, "DC", 20));

        int slowPicks = 0;
        Random random = new Random(42);
        int trials = 4000;
        for (int i = 0; i < trials; i++) {
            Human picked = svc.pickFouler(List.of(slow, fast), states, random);
            if (picked == slow) slowPicks++;
        }
        // Slow defender should win at least 60% of the picks (expected ~78%).
        assertTrue(slowPicks > trials * 0.6,
                "expected slow defender to be picked >60% of the time, got " + slowPicks + "/" + trials);
    }

    @Test
    void pickFouler_nullStates_fallsBackToUniform() {
        LiveMatchSimulationService svc = newService();
        Human a = human(1L, "A", "DC", 60);
        Human b = human(2L, "B", "DC", 60);
        int aPicks = 0;
        Random random = new Random(7);
        int trials = 4000;
        for (int i = 0; i < trials; i++) {
            if (svc.pickFouler(List.of(a, b), null, random) == a) aPicks++;
        }
        // With null states + uniform random, neither side should dominate.
        assertTrue(Math.abs(aPicks - trials / 2) < trials * 0.1,
                "expected ~50/50 with null states, got " + aPicks + "/" + trials);
    }

    // ---------------- pickWeightedAttacker (pace bonus) ----------------

    @Test
    void pickWeightedAttacker_paceBonus_quickerAttackerShootsMoreOften() {
        // Two attackers, identical rating + position. The quick one should
        // be chosen as shooter notably more often than the slow one.
        LiveMatchSimulationService svc = newService();
        Human slow = human(1L, "Slow ST", "ST", 70);
        Human fast = human(2L, "Fast ST", "ST", 70);
        Map<Long, PlayerMatchState> states = new HashMap<>();
        states.put(1L, state(1L, "ST", 2));
        states.put(2L, state(2L, "ST", 20));
        // Fresh stamina so the stamina factor doesn't distort the test.
        states.get(1L).currentStamina = 100;
        states.get(2L).currentStamina = 100;

        int fastPicks = 0;
        Random random = new Random(123);
        int trials = 4000;
        for (int i = 0; i < trials; i++) {
            // Minute 70 (>= fatigue threshold) so pace/stamina influence applies.
            Human picked = svc.pickWeightedAttacker(List.of(slow, fast), states, 70, random);
            if (picked == fast) fastPicks++;
        }
        // Fast attacker weight ~1.2x slow attacker → ~55% of picks. Allow a
        // generous margin (52-65%) to absorb RNG variance.
        assertTrue(fastPicks > trials * 0.52 && fastPicks < trials * 0.65,
                "expected fast attacker picked 52-65% of the time, got " + fastPicks + "/" + trials);
    }

    @Test
    void pickWeightedAttacker_empty_returnsNull() {
        LiveMatchSimulationService svc = newService();
        assertNull(svc.pickWeightedAttacker(List.of(), null, 70, new Random(0)));
    }

    // ---------------- helpers ----------------

    private static Human human(long id, String name, String position, double rating) {
        Human h = new Human();
        h.setId(id);
        h.setName(name);
        h.setPosition(position);
        h.setRating(rating);
        return h;
    }

    private static PlayerMatchState state(long id, String position, int pace) {
        PlayerMatchState s = new PlayerMatchState();
        s.playerId = id;
        s.position = position;
        s.pace = pace;
        s.staminaAttr = 10;
        s.naturalFitness = 10;
        s.currentStamina = 100;
        s.isOnPitch = true;
        return s;
    }
}
