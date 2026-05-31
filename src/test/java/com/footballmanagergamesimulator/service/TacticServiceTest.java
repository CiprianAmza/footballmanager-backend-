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
        // The value is a back-to-front SORT key (only relative order matters, not absolute index),
        // so assert ordering relationships rather than brittle exact indices. GK is the lowest, ST
        // the highest, and the Strat-3 fine positions (DM/AM*/WB*) must be present (not -1) so slot
        // sorting stays deterministic for them too.
        assertEquals(0, tacticService.getValueForTacticDisplay("GK"));
        assertTrue(tacticService.getValueForTacticDisplay("GK") < tacticService.getValueForTacticDisplay("DC"));
        assertTrue(tacticService.getValueForTacticDisplay("DC") < tacticService.getValueForTacticDisplay("DM"));
        assertTrue(tacticService.getValueForTacticDisplay("DM") < tacticService.getValueForTacticDisplay("MC"));
        assertTrue(tacticService.getValueForTacticDisplay("MC") < tacticService.getValueForTacticDisplay("AMC"));
        assertTrue(tacticService.getValueForTacticDisplay("AMC") < tacticService.getValueForTacticDisplay("ST"));
        for (String pos : new String[]{"DM", "AMC", "AML", "AMR", "WBL", "WBR"}) {
            assertTrue(tacticService.getValueForTacticDisplay(pos) >= 0, pos + " must be ordered");
        }

        // Test for invalid positions (not in the list)
        assertEquals(-1, tacticService.getValueForTacticDisplay("XYZ"));
        assertEquals(-1, tacticService.getValueForTacticDisplay(""));
    }

    @Test
    void testGetAllExistingTactics() {
        // Test the list of tactics
        List<String> expectedTactics = List.of("442", "433", "343", "451", "352",
                "4231", "4141", "4411", "4321", "4222", "3421", "532", "5212", "541", "3511");
        assertEquals(expectedTactics, tacticService.getAllExistingTactics());
    }
}
