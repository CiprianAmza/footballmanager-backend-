package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AnimationFixtures {
    static final String FIXTURE = "CTIM:501";
    static final long PLAN_SEED = 918_273_645L;
    static final long HOME = 10;
    static final long AWAY = 20;
    static final long SCORER = 110;
    static final long ASSISTER = 109;
    private static final String[] POSITIONS = {"GK", "DL", "DC", "DC", "DR", "DM", "MC", "ML", "MR", "AMC", "ST"};

    private AnimationFixtures() { }

    static List<PlayerSnapshot> side(long firstId, long teamId, String prefix) {
        return side(firstId, teamId, prefix, POSITIONS.length);
    }

    static List<PlayerSnapshot> side(long firstId, long teamId, String prefix, int count) {
        List<PlayerSnapshot> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new PlayerSnapshot(firstId + i, teamId, prefix + " " + (i + 1),
                    i + 1, POSITIONS[i], "ROLE_" + POSITIONS[i], 60 + i));
        }
        return result;
    }

    /** Test convenience: map a minute to a period. The engine itself never derives direction from the minute. */
    static MatchPeriod periodFor(int minute) {
        if (minute <= 45) return MatchPeriod.FIRST_HALF;
        if (minute <= 90) return MatchPeriod.SECOND_HALF;
        if (minute <= 105) return MatchPeriod.EXTRA_TIME_FIRST_HALF;
        return MatchPeriod.EXTRA_TIME_SECOND_HALF;
    }

    static MatchMomentSpec spec() {
        return spec(FIXTURE, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, ASSISTER);
    }

    static MatchMomentSpec spec(AnimationPhase phase, AnimationOutcome outcome,
                                int slot, int minute, Long assist) {
        return spec(FIXTURE, phase, outcome, slot, minute, assist);
    }

    static MatchMomentSpec spec(String fixture, AnimationPhase phase, AnimationOutcome outcome,
                                int slot, int minute, Long assist) {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return new MatchMomentSpec(fixture, slot, PLAN_SEED,
                AnimationDirector.CURRENT_GENERATOR_VERSION, minute, 2, periodFor(minute),
                HOME, AWAY, HOME, phase, outcome, SCORER, assist, players,
                new TacticalContext("ATTACKING", "BALANCED", 55, 48));
    }

    /** Spec with an explicit period and a custom roster/scorer/assist, used by the new coverage tests. */
    static MatchMomentSpec spec(MatchPeriod period, AnimationPhase phase, AnimationOutcome outcome, int slot,
                                int minute, long scoringTeam, long defendingTeam, long homeTeam,
                                long scorer, Long assist, List<PlayerSnapshot> players) {
        return new MatchMomentSpec(FIXTURE, slot, PLAN_SEED, AnimationDirector.CURRENT_GENERATOR_VERSION,
                minute, 2, period, scoringTeam, defendingTeam, homeTeam, phase, outcome, scorer, assist,
                players, new TacticalContext("ATTACKING", "BALANCED", 55, 48));
    }

    /** Standard 11v11 GOAL spec with an explicit period, holding everything else fixed. */
    static MatchMomentSpec specPeriod(MatchPeriod period, int slot, int minute, Long assist) {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return new MatchMomentSpec(FIXTURE, slot, PLAN_SEED, AnimationDirector.CURRENT_GENERATOR_VERSION,
                minute, 2, period, HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                SCORER, assist, players, new TacticalContext("ATTACKING", "BALANCED", 55, 48));
    }

    static void assertExact(AnimationReplay first, AnimationReplay second) {
        assertEquals(first, second);
        assertEquals(first.fingerprint(), second.fingerprint());
    }
}
