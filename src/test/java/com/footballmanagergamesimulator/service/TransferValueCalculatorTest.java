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
    void calculate_decayAccelerates_pastThirty() {
        long age29 = TransferValueCalculator.calculate(29, "ST", 100);
        long age31 = TransferValueCalculator.calculate(31, "ST", 100);
        long age34 = TransferValueCalculator.calculate(34, "ST", 100);

        assertTrue(age29 > age31, "value drops 29→31");
        assertTrue(age31 > age34, "value drops further 31→34");
        // The age-34 multiplier (0.08) is below half of age-31 (0.45),
        // so the later drop should be steeper relative to the earlier.
        assertTrue(age34 * 5L < age31, "post-33 decay should be steep");
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
