package com.footballmanagergamesimulator.animation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared builders for animation-engine tests: a standard 11v11 moment. */
final class AnimationTestFixtures {

    static final String FIXTURE_KEY = "1_5_3_10_20";
    static final long PLAN_SEED = 987_654_321L;
    static final long ATTACKING_TEAM = 10L;
    static final long DEFENDING_TEAM = 20L;
    static final long HOME_TEAM = 10L;
    static final long SCORER_ID = 110;   // the ST
    static final long ASSISTER_ID = 109; // the AMC

    private static final String[] FORMATION = {
            "GK", "DL", "DC", "DC", "DR", "DM", "MC", "ML", "MR", "AMC", "ST"};

    private AnimationTestFixtures() {
    }

    static List<PlayerSnapshot> eleven(long baseId, String namePrefix) {
        List<PlayerSnapshot> players = new ArrayList<>();
        for (int i = 0; i < FORMATION.length; i++) {
            players.add(new PlayerSnapshot(baseId + i, namePrefix + " " + (i + 1),
                    i + 1, FORMATION[i], 60 + i));
        }
        return players;
    }

    /** ST 110 scores, AMC 109 assists, minute 30, OPEN_PLAY GOAL, slot 0. */
    static MatchMomentSpec spec() {
        return spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, ASSISTER_ID);
    }

    static MatchMomentSpec spec(AnimationPhase phase, AnimationOutcome outcome,
                                int slotIndex, int minute, Long assisterId) {
        return spec(FIXTURE_KEY, phase, outcome, slotIndex, minute, assisterId);
    }

    static MatchMomentSpec spec(String fixtureKey, AnimationPhase phase, AnimationOutcome outcome,
                                int slotIndex, int minute, Long assisterId) {
        return new MatchMomentSpec(fixtureKey, slotIndex, PLAN_SEED,
                AnimationDirector.CURRENT_GENERATOR_VERSION, minute, 2,
                ATTACKING_TEAM, DEFENDING_TEAM, HOME_TEAM, phase, outcome,
                SCORER_ID, assisterId, eleven(100, "Att"), eleven(200, "Def"),
                new TacticalContext("attacking", "defensive"));
    }

    static void assertFramesIdentical(AnimationReplay a, AnimationReplay b) {
        assertEquals(a.frames().size(), b.frames().size(), "frame count");
        for (int f = 0; f < a.frames().size(); f++) {
            ReplayFrame fa = a.frames().get(f);
            ReplayFrame fb = b.frames().get(f);
            assertEquals(fa.ballX(), fb.ballX(), 0.0, "ballX frame " + f);
            assertEquals(fa.ballY(), fb.ballY(), 0.0, "ballY frame " + f);
            assertEquals(fa.ballCarrierId(), fb.ballCarrierId(), "carrier frame " + f);
            for (int i = 0; i < fa.positions().size(); i++) {
                assertEquals(fa.positions().get(i)[0], fb.positions().get(i)[0], 0.0,
                        "x frame " + f + " player " + i);
                assertEquals(fa.positions().get(i)[1], fb.positions().get(i)[1], 0.0,
                        "y frame " + f + " player " + i);
            }
        }
        assertEquals(a.events(), b.events(), "events");
        assertEquals(a.fingerprint(), b.fingerprint(), "fingerprint");
    }
}
