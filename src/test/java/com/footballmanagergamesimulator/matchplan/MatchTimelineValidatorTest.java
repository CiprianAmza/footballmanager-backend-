package com.footballmanagergamesimulator.matchplan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchTimelineValidatorTest {

    private Contributor p(long id) {
        return new Contributor(id, "P" + id, "MC", 15.0, 15, 15, 15, 100.0, false, false);
    }

    /** A valid 11-player starting XI: ids 1..11. */
    private List<Contributor> xi() {
        List<Contributor> xi = new ArrayList<>();
        for (long i = 1; i <= 11; i++) xi.add(p(i));
        return xi;
    }

    private List<Contributor> bench(long... ids) {
        List<Contributor> b = new ArrayList<>();
        for (long id : ids) b.add(p(id));
        return b;
    }

    private Lineup lineup(List<Contributor> xi, List<Contributor> bench, List<Lineup.SubMove> subs) {
        return new Lineup(xi, bench, subs);
    }

    @Test
    void validLineupWithSub_passes() {
        Lineup l = lineup(xi(), bench(20, 21),
                List.of(new Lineup.SubMove(0, 60, 2L, p(20))));
        assertDoesNotThrow(() -> MatchTimelineValidator.validate(l, 90));
    }

    @Test
    void notElevenStarters_rejected() {
        List<Contributor> ten = xi();
        ten.remove(0);
        assertThrows(IllegalStateException.class,
                () -> MatchTimelineValidator.validate(lineup(ten, bench(20), List.of()), 90));
    }

    @Test
    void duplicateStartingXi_rejected() {
        List<Contributor> dup = xi();
        dup.set(1, p(1)); // player 1 twice
        assertThrows(IllegalStateException.class,
                () -> MatchTimelineValidator.validate(lineup(dup, bench(20), List.of()), 90));
    }

    @Test
    void duplicateBench_rejected() {
        assertThrows(IllegalStateException.class,
                () -> MatchTimelineValidator.validate(lineup(xi(), bench(20, 20), List.of()), 90));
    }

    @Test
    void playerInBothXiAndBench_rejected() {
        assertThrows(IllegalStateException.class,
                () -> MatchTimelineValidator.validate(lineup(xi(), bench(1), List.of()), 90));
    }

    @Test
    void subbingOnAPlayerNotOnBench_rejected() {
        Lineup l = lineup(xi(), bench(20),
                List.of(new Lineup.SubMove(0, 60, 2L, p(99)))); // 99 not on bench
        assertThrows(IllegalStateException.class, () -> MatchTimelineValidator.validate(l, 90));
    }

    @Test
    void subbingOffAPlayerNotOnPitch_rejected() {
        Lineup l = lineup(xi(), bench(20),
                List.of(new Lineup.SubMove(0, 60, 99L, p(20)))); // 99 not on pitch
        assertThrows(IllegalStateException.class, () -> MatchTimelineValidator.validate(l, 90));
    }

    @Test
    void subbingOnAPlayerAlreadyOnPitch_rejected() {
        Lineup l = lineup(xi(), bench(20),
                List.of(new Lineup.SubMove(0, 50, 2L, p(20)),
                        new Lineup.SubMove(1, 60, 3L, p(20)))); // 20 brought on twice
        assertThrows(IllegalStateException.class, () -> MatchTimelineValidator.validate(l, 90));
    }

    @Test
    void reEntryAfterBeingSubbedOff_rejected() {
        Lineup l = lineup(xi(), bench(20, 21),
                List.of(new Lineup.SubMove(0, 50, 2L, p(20)),   // 20 on
                        new Lineup.SubMove(1, 60, 20L, p(21)),  // 20 off
                        new Lineup.SubMove(2, 70, 3L, p(20)))); // 20 tries to re-enter
        assertThrows(IllegalStateException.class, () -> MatchTimelineValidator.validate(l, 90));
    }

    @Test
    void substitutionMinuteOutOfRange_rejected() {
        Lineup l = lineup(xi(), bench(20),
                List.of(new Lineup.SubMove(0, 130, 2L, p(20)))); // beyond 120
        assertThrows(IllegalStateException.class, () -> MatchTimelineValidator.validate(l, 120));
    }
}
