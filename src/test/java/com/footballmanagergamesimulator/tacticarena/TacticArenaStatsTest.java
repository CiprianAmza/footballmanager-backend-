package com.footballmanagergamesimulator.tacticarena;

import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.MatchLine;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.Results;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.RunTotals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TacticArenaStatsTest {

    @Test
    void totalsSplitHomeAndAway() {
        RunTotals t = TacticArenaStats.totals(List.of(
                new MatchLine(true, 2, 0, 1.5, 0.4),
                new MatchLine(false, 1, 1, 0.9, 1.1),
                new MatchLine(false, 0, 3, null, null)));
        assertEquals(3, t.overall().matches());
        assertEquals(4, t.overall().points());
        assertEquals(1, t.overall().wins());
        assertEquals(1, t.overall().draws());
        assertEquals(1, t.overall().losses());
        assertEquals(3, t.home().points());
        assertEquals(1, t.away().points());
        assertEquals(2, t.away().matches());
        assertEquals(2, t.overall().xgMatches());
    }

    @Test
    void averagesPerSeasonLikeFmArena() {
        // Three seasons of 2 league matches each: points 6, 3, 1 -> 3.33 -> 3 of 6.
        RunTotals a = TacticArenaStats.totals(List.of(new MatchLine(true, 3, 1, 2.0, 1.0), new MatchLine(false, 2, 0, 1.0, 0.5)));
        RunTotals b = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 0, 1.0, 1.0), new MatchLine(false, 0, 2, 0.5, 1.5)));
        RunTotals c = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 1, 1.0, 1.0), new MatchLine(false, 0, 1, 0.5, 1.0)));
        Results r = TacticArenaStats.average(List.of(a, b, c));

        assertEquals(3, r.basedOn());
        assertEquals(6, r.overall().pld());
        assertEquals(3, r.overall().pts());
        assertEquals(6, r.overall().ptsMax());
        assertEquals(2, r.overall().gf());   // 7 / 3 = 2.33
        assertEquals(2, r.overall().ga());   // 5 / 3 = 1.67
        assertEquals(1, r.overall().gd());   // 2 / 3 = 0.67
        assertEquals(2, r.overall().xgf());  // 6.0 / 3
        assertEquals(2, r.overall().xga());  // 6.0 / 3
        assertEquals(0, r.overall().xgd());

        assertEquals(3, r.home().pld());
        assertEquals(2, r.home().pts());     // 3 + 3 + 1 = 7 / 3 = 2.33
        assertEquals(3, r.home().ptsMax());
        assertEquals(1, r.away().pts());     // 3 + 0 + 0 = 1
        assertEquals(0, r.away().gd());      // (2 - 3) / 3 = -0.33
    }

    @Test
    void negativeGoalDifferenceRoundsToNearest() {
        RunTotals a = TacticArenaStats.totals(List.of(new MatchLine(true, 0, 2, null, null)));
        RunTotals b = TacticArenaStats.totals(List.of(new MatchLine(true, 0, 1, null, null)));
        Results r = TacticArenaStats.average(List.of(a, b));
        assertEquals(-1, r.overall().gd()); // -3 / 2 = -1.5, rounded half up
    }

    @Test
    void xgIsNullWithoutStatsAndEmptyRunsGiveNull() {
        RunTotals a = TacticArenaStats.totals(List.of(new MatchLine(true, 1, 0, null, null)));
        Results r = TacticArenaStats.average(List.of(a));
        assertNull(r.overall().xgf());
        assertNull(r.overall().xgd());
        assertEquals(1, r.basedOn());
        assertNull(TacticArenaStats.average(List.of()));
    }
}
