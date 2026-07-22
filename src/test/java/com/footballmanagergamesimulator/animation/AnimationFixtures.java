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
        List<PlayerSnapshot> result = new ArrayList<>();
        for (int i = 0; i < POSITIONS.length; i++) {
            result.add(new PlayerSnapshot(firstId + i, teamId, prefix + " " + (i + 1),
                    i + 1, POSITIONS[i], "ROLE_" + POSITIONS[i], 60 + i));
        }
        return result;
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
                AnimationDirector.CURRENT_GENERATOR_VERSION, minute, 2,
                HOME, AWAY, HOME, phase, outcome, SCORER, assist, players,
                new TacticalContext("ATTACKING", "BALANCED", 55, 48));
    }

    static void assertExact(AnimationReplay first, AnimationReplay second) {
        assertEquals(first, second);
        assertEquals(first.fingerprint(), second.fingerprint());
    }
}
