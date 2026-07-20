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

    @Test
    void leagueOfChampionsUsesTieredTwentyOneClubAccess() {
        CompetitionFormat format = config.get(4);
        EuropeanFormatPlan plan = format.europeanPlan();
        assertNotNull(plan);
        assertEquals(21, plan.totalTeams());
        assertEquals(2, plan.preliminaryRounds());
        assertEquals(2, plan.stageForRound(0).bracketSize(), "two clubs enter qualifying round one");
        assertEquals(8, plan.stageForRound(1).bracketSize(), "winner plus seven clubs enter round two");
        assertEquals(16, plan.stageForRound(2).bracketSize(), "twelve direct plus four winners enter groups");
        assertEquals(2, plan.groupStartRound());
        assertEquals(10, plan.finalRound());
    }

    @Test
    void superCupIsSingleMatchKnockoutFormat() {
        CompetitionFormat format = config.get(6);
        assertEquals(CompetitionFormat.Kind.KNOCKOUT, format.kind());
        assertFalse(format.isTwoLeg(1));
    }
}
