package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.compartment.match.ExpectedPoints;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.MatchLine;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.Results;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.RunTotals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TacticArenaExpectedPointsTest {

    @Test
    void splitXptsIsAveragedLikePointsWithOneDecimal() {
        // Two seasons: 1.20 + 2.55 = 3.75 and 0.40 + 1.10 = 1.50 -> (3.75 + 1.50) / 2 = 2.625 -> 2.6.
        RunTotals a = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 1, 1.0, 1.0, 1.20),
                new MatchLine(false, 2, 0, 2.0, 0.5, 2.55)));
        RunTotals b = TacticArenaStats.totals(List.of(new MatchLine(true, 0, 1, 0.5, 1.5, 0.40),
                new MatchLine(false, 1, 0, 1.2, 0.8, 1.10)));
        Results r = TacticArenaStats.average(List.of(a, b));
        assertEquals(2.6, r.overall().xpts());
        assertEquals(0.8, r.home().xpts());   // (1.20 + 0.40) / 2
        assertEquals(1.8, r.away().xpts());   // (2.55 + 1.10) / 2 = 1.825 -> 1.8
        assertEquals(4, r.overall().pts());    // (4 + 3) / 2 = 3.5 -> 4: same matches, same averaging
    }

    @Test
    void xptsIsNullWhenNoMatchHasAValue() {
        RunTotals a = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 0, null, null, null)));
        assertNull(TacticArenaStats.average(List.of(a)).overall().xpts());
        // Only the matches with a value are summed (like xG).
        RunTotals mixed = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 0, null, null, null),
                new MatchLine(true, 1, 0, 1.0, 1.0, 1.5)));
        assertEquals(1.5, TacticArenaStats.average(List.of(mixed)).overall().xpts());
    }

    @Test
    void storedXptsWinsOverThePoissonFallback() {
        assertEquals(2.31, TacticArenaStats.expectedPoints(2.31, 0.2, 3.0));
        assertNull(TacticArenaStats.expectedPoints(null, null, 1.0));
        double fallback = TacticArenaStats.expectedPoints(null, 1.8, 0.7);
        assertEquals(ExpectedPoints.poisson(1.8, 0.7).homeXpts(), fallback, 1e-12);
        assertTrue(fallback > 1.5 && fallback < 3.0);
        assertEquals(TacticArenaStats.expectedPoints(null, 1.1, 1.1),
                3.0 - TacticArenaStats.expectedPoints(null, 1.1, 1.1) - ExpectedPoints.poisson(1.1, 1.1).draw(), 1e-12);
    }

    @Test
    void totalsStoredBeforeXptsStillReadAndGiveNull() throws Exception {
        String old = "{\"overall\":{\"matches\":2,\"points\":4,\"wins\":1,\"draws\":1,\"losses\":0,\"goalsFor\":3,"
                + "\"goalsAgainst\":1,\"xgFor\":2.5,\"xgAgainst\":1.0,\"xgMatches\":2},"
                + "\"home\":{\"matches\":1,\"points\":3,\"wins\":1,\"draws\":0,\"losses\":0,\"goalsFor\":2,"
                + "\"goalsAgainst\":0,\"xgFor\":1.5,\"xgAgainst\":0.5,\"xgMatches\":1},"
                + "\"away\":{\"matches\":1,\"points\":1,\"wins\":0,\"draws\":1,\"losses\":0,\"goalsFor\":1,"
                + "\"goalsAgainst\":1,\"xgFor\":1.0,\"xgAgainst\":0.5,\"xgMatches\":1}}";
        RunTotals totals = new ObjectMapper().readValue(old, RunTotals.class);
        assertEquals(4, totals.overall().points());
        assertEquals(0, totals.overall().xptsMatches());
        Results r = TacticArenaStats.average(List.of(totals));
        assertEquals(4, r.overall().pts());
        assertNull(r.overall().xpts());
        // And the new shape round-trips.
        RunTotals fresh = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 0, 1.0, 0.5, 2.0)));
        RunTotals back = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(fresh), RunTotals.class);
        assertEquals(fresh, back);
    }
}
