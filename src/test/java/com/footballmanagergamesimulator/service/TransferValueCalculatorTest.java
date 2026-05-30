package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick sanity tests for {@link TransferValueCalculator}. The exact magic
 * numbers (50K floor, age cutoffs) are part of the public contract since
 * multiple controllers + the squad generator depend on them.
 */
class TransferValueCalculatorTest {

    @Test
    void calculate_floorIsFiftyThousand() {
        // Tiny rating + ancient age → result clamps to the 50K floor.
        assertEquals(50_000L, TransferValueCalculator.calculate(35, "GK", 1));
    }

    @Test
    void calculate_youthGetsPremium_over25() {
        // Same rating, 21-year-old worth more than a 27-year-old.
        long young = TransferValueCalculator.calculate(21, "ST", 100);
        long settled = TransferValueCalculator.calculate(27, "ST", 100);
        assertTrue(young > settled,
                "21yo should be valued above 27yo at the same rating, got " + young + " vs " + settled);
    }

    @Test
    void calculate_primePlateauThenCliffAt34() {
        long age27 = TransferValueCalculator.calculate(27, "ST", 100);
        long age33 = TransferValueCalculator.calculate(33, "ST", 100);
        long age34 = TransferValueCalculator.calculate(34, "ST", 100);

        // Extended prime: only a gentle taper across the 24-33 plateau.
        assertTrue(age27 > age33, "prime tapers gently 27→33");
        // The single-year drop at the 33→34 cliff exceeds the whole 27→33 taper.
        assertTrue(age33 - age34 > age27 - age33, "post-33 cliff is steeper than the prime taper");
    }

    @Test
    void calculate_ratingScalesCubically() {
        long r50  = TransferValueCalculator.calculate(25, "MC", 50);
        long r100 = TransferValueCalculator.calculate(25, "MC", 100);
        long r200 = TransferValueCalculator.calculate(25, "MC", 200);
        // Roughly: doubling rating gives ~8x value (within fudge-factor for clamps).
        assertTrue(r100 > r50 * 6, "doubling rating should be roughly 6-10x");
        assertTrue(r200 > r100 * 6, "doubling rating again should be roughly 6-10x");
    }
}
