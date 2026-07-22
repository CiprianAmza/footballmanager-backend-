package com.footballmanagergamesimulator.matchplan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Pure appearance-timeline derivation: starters, substitutions, minutes played. */
class MatchAppearanceTest {

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    @Test
    void starterWhoFinishes_hasNullExitAndFullMinutes() {
        Lineup lineup = new Lineup(List.of(p(1, "ST")), List.of());
        Lineup.Appearance a = lineup.appearances().get(0);
        assertEquals(0, a.startMinute());
        assertNull(a.exitMinute(), "a finisher never exits");
        assertEquals(90, a.minutesPlayed(90));
    }

    @Test
    void finisher_isOnPitchAtMinute90And120() {
        // The boundary the planner can hit: a goal at 90' (or 120' in ET) belongs
        // to a player who finished the match.
        Lineup.Appearance a = new Lineup(List.of(p(1, "ST")), List.of()).appearances().get(0);
        assertTrue(a.onPitchAt(90), "finisher must be on the pitch at 90'");
        assertTrue(a.onPitchAt(120), "finisher must be on the pitch at 120'");
        assertEquals(120, a.minutesPlayed(120));
    }

    @Test
    void substitution_splitsMinutesBetweenOffAndOn() {
        Lineup lineup = new Lineup(List.of(p(1, "ST"), p(2, "GK")),
                List.of(new Lineup.SubMove(0, 60, 1L, p(9, "ST"))));
        Map<Long, Lineup.Appearance> byPlayer = lineup.appearances().stream()
                .collect(Collectors.toMap(Lineup.Appearance::playerId, a -> a));

        assertEquals(60, byPlayer.get(1L).exitMinute());   // starter subbed off at 60
        assertEquals(60, byPlayer.get(1L).minutesPlayed(90));
        assertFalse(byPlayer.get(1L).onPitchAt(60));       // off exactly at 60
        assertEquals(60, byPlayer.get(9L).startMinute());  // sub came on at 60
        assertNull(byPlayer.get(9L).exitMinute());
        assertEquals(30, byPlayer.get(9L).minutesPlayed(90));
        assertEquals(90, byPlayer.get(2L).minutesPlayed(90)); // GK untouched
    }

    @Test
    void appearances_matchOnPitchAtEveryMinute() {
        // The timeline must agree with onPitchAt() — same source, no divergence.
        Lineup lineup = new Lineup(List.of(p(1, "ST"), p(2, "MC"), p(3, "DC")),
                List.of(new Lineup.SubMove(0, 70, 2L, p(9, "MC"))));
        List<Lineup.Appearance> apps = lineup.appearances();

        for (int minute = 0; minute <= 90; minute++) {
            int m = minute;
            var onPitchIds = lineup.onPitchAt(minute).stream().map(Contributor::playerId).sorted().toList();
            var timelineIds = apps.stream().filter(a -> a.onPitchAt(m))
                    .map(Lineup.Appearance::playerId).sorted().toList();
            assertEquals(onPitchIds, timelineIds, "mismatch at minute " + minute);
        }
    }
}
