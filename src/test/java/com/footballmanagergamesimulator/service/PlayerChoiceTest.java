package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerPersonality;
import com.footballmanagergamesimulator.service.PlayerChoice.Club;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerChoiceTest {

    private static final Club AI = new Club(9_000, 5_000, 2);

    @Test
    @DisplayName("""
            GIVEN an AI club paying 9000, of reputation 5000, with 2 better players at his position
            WHEN players of each personality compare it with my club
            THEN MONEY follows the higher wage, FAME the higher reputation, AMBITION the fewer better players;
             a tie leaves him with the AI club
            """)
    void personalityDecidesTheClub() {
        assertTrue(PlayerChoice.choosesMe(PlayerPersonality.MONEY, AI, new Club(9_001, 1, 9)));
        assertFalse(PlayerChoice.choosesMe(PlayerPersonality.MONEY, AI, new Club(9_000, 9_999, 0)));
        assertTrue(PlayerChoice.choosesMe(PlayerPersonality.FAME, AI, new Club(1, 5_001, 9)));
        assertFalse(PlayerChoice.choosesMe(PlayerPersonality.FAME, AI, new Club(99_999, 5_000, 0)));
        assertTrue(PlayerChoice.choosesMe(PlayerPersonality.AMBITION, AI, new Club(1, 1, 1)));
        assertFalse(PlayerChoice.choosesMe(PlayerPersonality.AMBITION, AI, new Club(99_999, 9_999, 2)));
    }
}
