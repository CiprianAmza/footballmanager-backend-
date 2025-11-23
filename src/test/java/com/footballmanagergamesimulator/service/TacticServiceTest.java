package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class TacticServiceTest {

    private final TacticService tacticService = new TacticService();

    @Test
    void testGetRoomInTeamByTactic() {
        // Test for existing tactics
        Map<String, Integer> expected442 = Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 2);
        assertEquals(expected442, tacticService.getRoomInTeamByTactic("442"));

        Map<String, Integer> expected433 = Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 3);
        assertEquals(expected433, tacticService.getRoomInTeamByTactic("433"));

        // Test for non-existing tactic (default to 442)
        assertEquals(expected442, tacticService.getRoomInTeamByTactic("999"));
    }

    @Test
    void testGetValueForTacticDisplay() {
        // Test for valid positions
        assertEquals(0, tacticService.getValueForTacticDisplay("GK"));
        assertEquals(1, tacticService.getValueForTacticDisplay("DL"));
        assertEquals(2, tacticService.getValueForTacticDisplay("DC"));
        assertEquals(3, tacticService.getValueForTacticDisplay("DR"));
        assertEquals(4, tacticService.getValueForTacticDisplay("ML"));
        assertEquals(5, tacticService.getValueForTacticDisplay("MC"));
        assertEquals(6, tacticService.getValueForTacticDisplay("MR"));
        assertEquals(7, tacticService.getValueForTacticDisplay("ST"));

        // Test for invalid positions (not in the list)
        assertEquals(-1, tacticService.getValueForTacticDisplay("XYZ"));
        assertEquals(-1, tacticService.getValueForTacticDisplay(""));
    }

    @Test
    void testGetAllExistingTactics() {
        // Test the list of tactics
        List<String> expectedTactics = List.of("442", "433", "343", "451", "352");
        assertEquals(expectedTactics, tacticService.getAllExistingTactics());
    }
}
