package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the derivation of the configurable European format plan: how many
 * preliminary / group / knockout rounds a given (totalTeams, groupCount,
 * groupSize, qualifyPerGroup) shape produces, and the per-round descriptors the
 * dispatcher/calendar/coefficient logic will read.
 */
class EuropeanFormatPlanTest {

    @Test
    void fortyTeams_fourGroupsOfFour_twoQualify() {
        EuropeanFormatPlan p = EuropeanFormatPlan.derive(40, 4, 4, 2);

        assertEquals(16, p.slots());
        assertEquals(8, p.qualifiers());
        // 40 → eliminate min(24,20)=20 → 20 → eliminate min(4,10)=4 → 16  ⇒ 2 prelim rounds
        assertEquals(2, p.preliminaryRounds());
        assertEquals(6, p.groupRounds());            // (4-1)*2 double round-robin
        assertEquals(3, p.knockoutRounds());         // log2(8) = QF, SF, Final
        assertEquals(11, p.totalRounds());

        assertEquals(2, p.groupStartRound());          // groups start after the 2 prelim rounds
        assertEquals(7, p.groupEndRound());
        assertEquals(8, p.knockoutStartRound());
        assertEquals(10, p.finalRound());

        // Preliminary rounds: seeded, single-leg, with the right field sizes.
        assertEquals(EuropeanPhase.PRELIMINARY, p.stageForRound(0).phase());
        assertEquals(40, p.stageForRound(0).bracketSize());
        assertTrue(p.stageForRound(0).seededDraw());
        assertFalse(p.stageForRound(0).twoLeg());
        assertEquals(20, p.stageForRound(1).bracketSize());

        // Group rounds: draw on first, qualify on last.
        assertEquals(EuropeanPhase.GROUP, p.stageForRound(2).phase());
        assertTrue(p.stageForRound(2).groupDraw());
        assertFalse(p.stageForRound(2).qualify());
        assertTrue(p.stageForRound(7).qualify());
        assertFalse(p.stageForRound(7).groupDraw());

        // Knockout: QF (8) and SF (9) two-leg + entry round seeded; Final (10) single-leg.
        assertEquals(EuropeanPhase.KNOCKOUT, p.stageForRound(8).phase());
        assertEquals(3, p.stageForRound(8).roundsFromFinal());
        assertTrue(p.stageForRound(8).seededDraw());
        assertTrue(p.stageForRound(8).twoLeg());
        assertEquals(2, p.stageForRound(9).roundsFromFinal());
        assertTrue(p.stageForRound(9).twoLeg());
        assertEquals(1, p.stageForRound(10).roundsFromFinal());
        assertFalse(p.stageForRound(10).twoLeg());     // final is single-leg
    }

    @Test
    void sixteenTeams_noPreliminaryRounds() {
        EuropeanFormatPlan p = EuropeanFormatPlan.derive(16, 4, 4, 2);
        assertEquals(0, p.preliminaryRounds());
        assertEquals(6, p.groupRounds());
        assertEquals(3, p.knockoutRounds());
        assertEquals(9, p.totalRounds());
        assertEquals(0, p.groupStartRound());
        assertTrue(p.stageForRound(0).groupDraw());
        assertEquals(8, p.finalRound());
    }

    @Test
    void twentyFourTeams_oneTrimRound() {
        EuropeanFormatPlan p = EuropeanFormatPlan.derive(24, 4, 4, 2);
        // 24 → eliminate min(8,12)=8 → 16 ⇒ 1 prelim round
        assertEquals(1, p.preliminaryRounds());
        assertEquals(24, p.stageForRound(0).bracketSize());
        assertEquals(EuropeanPhase.GROUP, p.stageForRound(1).phase());
    }

    @Test
    void eightGroups_largerKnockout() {
        // 8 groups of 4, top 2 → 16 qualifiers → 4 knockout rounds (R16, QF, SF, Final).
        EuropeanFormatPlan p = EuropeanFormatPlan.derive(40, 8, 4, 2);
        assertEquals(32, p.slots());
        assertEquals(16, p.qualifiers());
        assertEquals(1, p.preliminaryRounds());        // 40 → 32
        assertEquals(6, p.groupRounds());
        assertEquals(4, p.knockoutRounds());
    }

    @Test
    void rejectsInvalidShapes() {
        assertThrows(IllegalArgumentException.class, () -> EuropeanFormatPlan.derive(40, 3, 4, 2),
                "odd group count");
        assertThrows(IllegalArgumentException.class, () -> EuropeanFormatPlan.derive(40, 4, 4, 3),
                "qualifiers 12 is not a power of two");
        assertThrows(IllegalArgumentException.class, () -> EuropeanFormatPlan.derive(10, 4, 4, 2),
                "totalTeams below the 16 group slots");
        assertThrows(IllegalArgumentException.class, () -> EuropeanFormatPlan.derive(40, 4, 2, 4),
                "qualifyPerGroup exceeds groupSize");
    }
}
