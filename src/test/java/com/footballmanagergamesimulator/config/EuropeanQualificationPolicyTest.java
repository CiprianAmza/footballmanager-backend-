package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EuropeanQualificationPolicyTest {

    @Test
    void allocationFillsTheDocumentedTwentyOnePlaces() {
        EuropeanQualificationPolicy policy = new EuropeanQualificationPolicy();
        assertEquals(12, policy.directEntrants());
        assertEquals(7, policy.qualifyingEntrants());
        assertEquals(2, policy.preliminaryEntrants());
        assertEquals(21, policy.totalEntrants());
        assertEquals(2, policy.preliminaryForRank(7));
        assertEquals(0, policy.directForRank(7));
    }
}
