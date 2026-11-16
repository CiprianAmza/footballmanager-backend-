package com.footballmanagergamesimulator.algorithms;

import com.footballmanagergamesimulator.algorithms.DiamondLeagueScheduleGenerator.Fixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiamondLeagueScheduleGeneratorTest {

    private static final List<Long> CLUBS = List.of(11L, 25L, 3L, 47L, 52L, 60L, 71L, 89L);

    @Test
    void eightClubsFourMeetingsGive28RoundsOf4Matches() {
        List<List<Fixture>> rounds = DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 42L);
        assertEquals(28, rounds.size());
        assertEquals(112, rounds.stream().mapToInt(List::size).sum());
        rounds.forEach(round -> assertEquals(4, round.size()));
    }

    @Test
    void everyClubPlaysExactlyOncePerRound() {
        for (List<Fixture> round : DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 7L)) {
            Set<Long> seen = new HashSet<>();
            for (Fixture f : round) {
                assertTrue(seen.add(f.homeTeamId()), "club twice in a round: " + f.homeTeamId());
                assertTrue(seen.add(f.awayTeamId()), "club twice in a round: " + f.awayTeamId());
            }
            assertEquals(new HashSet<>(CLUBS), seen);
        }
    }

    @Test
    void everyPairMeetsFourTimesTwiceAtEachGround() {
        Map<String, Integer> hosted = new HashMap<>();
        for (List<Fixture> round : DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 123L)) {
            for (Fixture f : round) {
                assertNotEquals(f.homeTeamId(), f.awayTeamId());
                hosted.merge(f.homeTeamId() + ">" + f.awayTeamId(), 1, Integer::sum);
            }
        }
        for (long a : CLUBS) {
            for (long b : CLUBS) {
                if (a == b) continue;
                assertEquals(2, hosted.getOrDefault(a + ">" + b, 0), a + " hosting " + b);
            }
        }
    }

    @Test
    void everyClubHas28MatchesFourteenAtHome() {
        Map<Long, Integer> home = new HashMap<>();
        Map<Long, Integer> away = new HashMap<>();
        for (List<Fixture> round : DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 99L)) {
            for (Fixture f : round) {
                home.merge(f.homeTeamId(), 1, Integer::sum);
                away.merge(f.awayTeamId(), 1, Integer::sum);
            }
        }
        for (long club : CLUBS) {
            assertEquals(14, home.get(club));
            assertEquals(14, away.get(club));
        }
    }

    @Test
    void consecutiveRoundsNeverRepeatAPairing() {
        for (long seed = 0; seed < 50; seed++) {
            List<List<Fixture>> rounds = DiamondLeagueScheduleGenerator.generate(CLUBS, 4, seed);
            for (int r = 1; r < rounds.size(); r++) {
                Set<String> previous = pairs(rounds.get(r - 1));
                for (String pair : pairs(rounds.get(r))) {
                    assertTrue(!previous.contains(pair), "seed " + seed + " repeats " + pair + " in round " + (r + 1));
                }
            }
        }
    }

    @Test
    void sameSeedSameScheduleWhateverTheInputOrder() {
        List<Long> reversed = new ArrayList<>(CLUBS);
        java.util.Collections.reverse(reversed);
        assertEquals(DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 2026L),
                DiamondLeagueScheduleGenerator.generate(reversed, 4, 2026L));
        assertNotEquals(DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 2026L),
                DiamondLeagueScheduleGenerator.generate(CLUBS, 4, 2027L));
    }

    @Test
    void rejectsOddFieldsOddEncountersAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> DiamondLeagueScheduleGenerator.generate(List.of(1L, 2L, 3L), 4, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> DiamondLeagueScheduleGenerator.generate(CLUBS, 3, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> DiamondLeagueScheduleGenerator.generate(List.of(1L, 1L, 2L, 3L), 4, 1L));
    }

    private static Set<String> pairs(List<Fixture> round) {
        Set<String> keys = new HashSet<>();
        for (Fixture f : round) {
            keys.add(Math.min(f.homeTeamId(), f.awayTeamId()) + "-" + Math.max(f.homeTeamId(), f.awayTeamId()));
        }
        return keys;
    }
}
