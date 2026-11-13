package com.footballmanagergamesimulator.service.odds;

import com.footballmanagergamesimulator.service.CupBracketService;
import com.footballmanagergamesimulator.service.odds.CupOddsSimulation.Match;
import com.footballmanagergamesimulator.service.odds.CupOddsSimulation.Odds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CupOddsSimulationTest {

    @Test
    @DisplayName("""
            GIVEN semi-finals 10 v 20 and 30 v 40 and a final still to fill, where the home side always goes through
            WHEN the cup is played out
            THEN 10 (home in semi 1, then team1 of the final) wins every time; 10 and 30 reach the final every time
            """)
    void winnersFollowTheBracket() {
        List<Match> bracket = List.of(
                new Match(1, 1, 10, 20, false, 0), new Match(1, 2, 30, 40, false, 0),
                new Match(2, 1, 0, 0, false, 0));

        Map<Long, Odds> odds = byTeam(CupOddsSimulation.simulate(bracket, (a, b) -> 1.0, 500, 1));

        assertEquals(1.0, odds.get(10L).win());
        assertEquals(1.0, odds.get(10L).reachFinal());
        assertEquals(1.0, odds.get(30L).reachFinal());
        assertEquals(0.0, odds.get(30L).win());
        assertFalse(odds.containsKey(20L));
    }

    @Test
    @DisplayName("""
            GIVEN semi-final 1 already won by 10 (already moved into the final) and semi-final 2 a 50/50
            WHEN the cup is played out 10,000 times
            THEN 10 is in the final every time; 30 and 40 about half the time each
            """)
    void aPlayedTieIsNotReplayed() {
        List<Match> bracket = List.of(
                new Match(1, 1, 10, 20, true, 0), new Match(1, 2, 30, 40, false, 0),
                new Match(2, 1, 10, 0, false, 0));

        Map<Long, Odds> odds = byTeam(CupOddsSimulation.simulate(bracket, (a, b) -> 0.5, 10_000, 5));

        assertEquals(1.0, odds.get(10L).reachFinal());
        assertEquals(0.5, odds.get(30L).reachFinal(), 0.02);
        assertEquals(0.5, odds.get(40L).reachFinal(), 0.02);
        assertFalse(odds.containsKey(20L));
    }

    @Test
    @DisplayName("""
            GIVEN a final already played and won by 30
            WHEN the cup is played out
            THEN 30 has won it, certainly
            """)
    void aPlayedFinalIsDecided() {
        List<Match> bracket = List.of(new Match(2, 1, 10, 30, true, 30));

        Map<Long, Odds> odds = byTeam(CupOddsSimulation.simulate(bracket, (a, b) -> 0.9, 100, 2));

        assertEquals(1.0, odds.get(30L).win());
        assertEquals(0.0, odds.get(10L).win());
    }

    @Test
    @DisplayName("""
            GIVEN a 10-team cup: 2 preliminary matches (seeds 7 v 10, 8 v 9) feeding a round of 8
            WHEN the bracket moves the preliminary winners on
            THEN the 7 v 10 winner meets seed 2 (match 3, second slot), the 8 v 9 winner meets seed 1 (match 1,
             second slot); later rounds halve (match 3 -> match 2 first slot, match 4 -> match 2 second slot)
            """)
    void preliminaryWinnersTakeTheirSeedsPlace() {
        assertArrayEquals(new int[]{3, 2}, CupBracketService.nextSlot(2, 4, 1));
        assertArrayEquals(new int[]{1, 2}, CupBracketService.nextSlot(2, 4, 2));
        assertArrayEquals(new int[]{2, 1}, CupBracketService.nextSlot(4, 2, 3));
        assertArrayEquals(new int[]{2, 2}, CupBracketService.nextSlot(4, 2, 4));
        assertNull(CupBracketService.nextSlot(2, 0, 1));
    }

    private static Map<Long, Odds> byTeam(List<Odds> odds) {
        return odds.stream().collect(Collectors.toMap(Odds::teamId, o -> o));
    }
}
