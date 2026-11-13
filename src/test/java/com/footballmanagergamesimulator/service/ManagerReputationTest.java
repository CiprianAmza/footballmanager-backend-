package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagerReputationTest {

    @Test
    @DisplayName("""
            GIVEN clubs with reputation 9,200, 2,600 and 10,200
            WHEN an AI manager takes over each
            THEN he starts with the club's reputation (9,200 and 2,600), capped at 10,000
            """)
    void aiManagerStartsWithHisClubsReputation() {
        assertEquals(9_200, ManagerReputation.startingFor(9_200));
        assertEquals(2_600, ManagerReputation.startingFor(2_600));
        assertEquals(10_000, ManagerReputation.startingFor(10_200));
    }
}
