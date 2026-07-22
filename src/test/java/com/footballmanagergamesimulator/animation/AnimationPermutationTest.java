package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same set of players in any order must produce the exact same animation. */
class AnimationPermutationTest {
    private final AnimationDirector director = new AnimationDirector();

    private static List<PlayerSnapshot> roster() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return players;
    }

    private AnimationReplay directFor(List<PlayerSnapshot> players, AnimationOutcome outcome, Long assist) {
        return director.direct(spec(MatchPeriod.FIRST_HALF, AnimationPhase.OPEN_PLAY, outcome, 0, 30,
                HOME, AWAY, HOME, SCORER, assist, players)).replay();
    }

    @Test void reversedListYieldsIdenticalReplayAndFingerprint() {
        for (AnimationOutcome outcome : AnimationOutcome.values()) for (Long assist : new Long[]{ASSISTER, null}) {
            List<PlayerSnapshot> forward = roster();
            List<PlayerSnapshot> reversed = new ArrayList<>(forward);
            Collections.reverse(reversed);
            AnimationReplay a = directFor(forward, outcome, assist);
            AnimationReplay b = directFor(reversed, outcome, assist);
            assertExact(a, b);
        }
    }

    @Test void manyShuffledOrdersAreAllIdentical() {
        AnimationReplay reference = directFor(roster(), AnimationOutcome.GOAL, ASSISTER);
        for (int seed = 1; seed <= 40; seed++) {
            List<PlayerSnapshot> shuffled = roster();
            Collections.shuffle(shuffled, new Random(seed));
            AnimationReplay candidate = directFor(shuffled, AnimationOutcome.GOAL, ASSISTER);
            assertEquals(reference.fingerprint(), candidate.fingerprint(), "order seed " + seed);
            assertEquals(reference, candidate);
        }
    }

    @Test void permutationIndependenceHoldsAcrossPlanSeedsAndFixtures() {
        Random orders = new Random(7);
        for (int i = 0; i < 25; i++) {
            List<PlayerSnapshot> forward = roster();
            List<PlayerSnapshot> shuffled = new ArrayList<>(forward);
            Collections.shuffle(shuffled, orders);
            long planSeed = 1_000L + i * 977L;
            String fixture = "CTIM:" + (600 + i);
            MatchMomentSpec base = new MatchMomentSpec(fixture, i % 4, planSeed,
                    AnimationDirector.CURRENT_GENERATOR_VERSION, 20 + i, 2, MatchPeriod.SECOND_HALF,
                    HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER,
                    i % 2 == 0 ? ASSISTER : null, forward, null);
            MatchMomentSpec permuted = new MatchMomentSpec(fixture, i % 4, planSeed,
                    AnimationDirector.CURRENT_GENERATOR_VERSION, 20 + i, 2, MatchPeriod.SECOND_HALF,
                    HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER,
                    i % 2 == 0 ? ASSISTER : null, shuffled, null);
            assertExact(director.direct(base).replay(), director.direct(permuted).replay());
        }
    }
}
