package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Leagues must adapt the number of meetings to their team count from a single
 * config source (shared by the game and the outcome-simulation tests). These
 * assertions pin that mapping so a format change is caught here, not in prod.
 */
class CompetitionFormatConfigTest {

    private CompetitionFormatConfig config;

    @BeforeEach
    void setUp() {
        config = new CompetitionFormatConfig();
    }

    @Test
    void leagueEncountersScaleWithTeamCount() {
        // typeId 1 = first league, 3 = second league — same mapping.
        for (int typeId : new int[]{1, 3}) {
            CompetitionFormat f = config.get(typeId);
            assertEquals(4, f.encountersFor(12), "12-team league plays quadruple round-robin");
            assertEquals(3, f.encountersFor(14), "14-team league plays triple round-robin");
            // Everything else falls back to the default of 2 (double round-robin),
            // regardless of parity or size.
            assertEquals(2, f.encountersFor(8), "8-team league defaults to double round-robin");
            assertEquals(2, f.encountersFor(10));
            assertEquals(2, f.encountersFor(16));
            assertEquals(2, f.encountersFor(20));
            assertEquals(2, f.encountersFor(11), "odd size with no override defaults to 2");
        }
    }

    @Test
    void unknownTypeFallsBackToPlainLeagueWithDefaultEncounters() {
        // get() must never return null — unknown types get a plain LEAGUE format.
        CompetitionFormat f = config.get(99);
        assertNotNull(f);
        assertEquals(2, f.encountersFor(8));
        assertEquals(2, f.encountersFor(13));
    }
}
