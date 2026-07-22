package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * direct() is total across the whole accepted profile domain crossed with participant position and
 * roster size. Every supported position (including a goalkeeper and both wing-backs) for both the
 * scorer and the assister, every 1..11 roster, every period, phase and outcome, at the slowest and
 * the boundary profiles, must render without throwing and within the configured physical limits.
 */
class AnimationProfilePositionTest {
    private static final long HOME = 10;
    private static final long AWAY = 20;
    /** All 14 supported tactical positions. */
    private static final String[] POSITIONS =
            {"GK", "DL", "WBL", "DC", "DR", "WBR", "DM", "ML", "MC", "MR", "AML", "AMC", "AMR", "ST"};
    private static final AnimationPhysicsProfile SLOWEST = new AnimationPhysicsProfile(0.3, 0.1, 1.0);
    private static final AnimationPhysicsProfile[] BOUNDARY = {
            AnimationPhysicsProfile.defaults(),
            new AnimationPhysicsProfile(0.6, 0.25, 4.0),
            SLOWEST,
            new AnimationPhysicsProfile(0.3, 100.0, 1.0),
            new AnimationPhysicsProfile(2.0, 0.6, 6.0)
    };

    private static PlayerSnapshot player(long id, long team, String pos) {
        return new PlayerSnapshot(id, team, "P" + id, (int) (id % 99), pos, "ROLE_" + pos, 60);
    }

    /** Attackers of size n with the scorer/assister at the requested positions, rest filled. */
    private static List<PlayerSnapshot> attackers(String scorerPos, String assisterPos, int n) {
        String[] fill = {"MC", "DM", "AMC", "DL", "DR", "ML", "MR", "AML", "AMR", "DC", "ST"};
        List<PlayerSnapshot> a = new ArrayList<>();
        a.add(player(HOME * 100 + 1, HOME, scorerPos));
        if (n >= 2) a.add(player(HOME * 100 + 2, HOME, assisterPos));
        for (int i = 2; i < n; i++) a.add(player(HOME * 100 + i + 1, HOME, fill[(i - 2) % fill.length]));
        return a;
    }

    private static List<PlayerSnapshot> defenders(int m) {
        String[] pos = {"GK", "DC", "DL", "DR", "DM", "MC", "WBL", "WBR", "ML", "MR", "ST"};
        List<PlayerSnapshot> d = new ArrayList<>();
        for (int i = 0; i < m; i++) d.add(player(AWAY * 100 + i + 1, AWAY, pos[i]));
        return d;
    }

    private static MatchMomentSpec spec(AnimationPhase phase, AnimationOutcome outcome, MatchPeriod period,
                                        String scorerPos, String assisterPos, boolean assisted,
                                        int attackers, int defenders) {
        List<PlayerSnapshot> players = new ArrayList<>(attackers(scorerPos, assisterPos, attackers));
        players.addAll(defenders(defenders));
        Long assist = assisted ? HOME * 100 + 2 : null;
        return new MatchMomentSpec("CTIM:PP", 0, 314L, AnimationDirector.CURRENT_GENERATOR_VERSION,
                minuteFor(period), 2, period, HOME, AWAY, HOME, phase, outcome, HOME * 100 + 1, assist, players, null);
    }

    @Test void everySupportedPositionPairAtTheSlowestProfileIsTotal() {
        AnimationDirector director = new AnimationDirector(SLOWEST);
        AnimationInvariantValidator validator = new AnimationInvariantValidator(SLOWEST);
        int checked = 0;
        for (String scorerPos : POSITIONS)
            for (String assisterPos : POSITIONS) {
                if (scorerPos.equals(assisterPos)) continue; // distinct players; both positions still covered by other pairs
                for (MatchPeriod period : MatchPeriod.values())
                    for (AnimationPhase phase : AnimationPhase.values())
                        for (AnimationOutcome outcome : AnimationOutcome.values()) {
                            MatchMomentSpec spec = spec(phase, outcome, period, scorerPos, assisterPos, true, 11, 11);
                            AnimationReplay replay = assertDoesNotThrow(() -> director.direct(spec).replay(),
                                    scorerPos + "/" + assisterPos + "/" + period + "/" + phase + "/" + outcome);
                            assertTrue(validator.validate(replay, spec).isEmpty(),
                                    "invalid " + scorerPos + "/" + assisterPos + "/" + phase + "/" + outcome);
                            checked++;
                        }
            }
        assertTrue(checked > 3000, "matrix " + checked);
    }

    @Test void everyRosterSizeAndBoundaryProfileWithDeepParticipantsIsTotal() {
        String[][] pairs = {{"ST", "GK"}, {"GK", "ST"}, {"WBL", "WBR"}, {"DC", "AMR"}, {"GK", "GK"}};
        int checked = 0;
        for (AnimationPhysicsProfile profile : BOUNDARY) {
            AnimationDirector director = new AnimationDirector(profile);
            AnimationInvariantValidator validator = new AnimationInvariantValidator(profile);
            for (String[] pair : pairs)
                for (int n = 1; n <= 11; n++)
                    for (int m = 1; m <= 11; m++)
                        for (boolean assisted : new boolean[]{true, false}) {
                            if (assisted && n < 2) continue; // need a distinct assister
                            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                                MatchMomentSpec spec = spec(AnimationPhase.OPEN_PLAY, outcome,
                                        MatchPeriod.EXTRA_TIME_FIRST_HALF, pair[0], pair[1], assisted, n, m);
                                AnimationReplay replay = assertDoesNotThrow(() -> director.direct(spec).replay(),
                                        profile.maxPlayerStep() + "/" + pair[0] + "/" + pair[1] + "/" + n + "v" + m
                                                + "/assist=" + assisted + "/" + outcome);
                                assertTrue(validator.validate(replay, spec).isEmpty());
                                checked++;
                            }
                        }
        }
        assertTrue(checked > 1000, "matrix " + checked);
    }

    private static int minuteFor(MatchPeriod period) {
        return switch (period) {
            case FIRST_HALF -> 20;
            case SECOND_HALF -> 70;
            case EXTRA_TIME_FIRST_HALF -> 95;
            case EXTRA_TIME_SECOND_HALF -> 115;
        };
    }
}
