package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic property/fuzz sweep across seeds, orders, phases, outcomes and periods. */
class AnimationFuzzTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());
    private static final AnimationPhase[] PHASES = AnimationPhase.values();
    private static final AnimationOutcome[] OUTCOMES = AnimationOutcome.values();
    private static final MatchPeriod[] PERIODS = MatchPeriod.values();

    private static List<PlayerSnapshot> roster() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return players;
    }

    @Test void everySeededScenarioIsValidAndOrderInvariant() {
        Random meta = new Random(20260722L);
        int scenarios = 0;
        for (int i = 0; i < 220; i++) {
            AnimationPhase phase = PHASES[meta.nextInt(PHASES.length)];
            AnimationOutcome outcome = OUTCOMES[meta.nextInt(OUTCOMES.length)];
            MatchPeriod period = PERIODS[meta.nextInt(PERIODS.length)];
            Long assist = meta.nextBoolean() ? ASSISTER : null;
            int slot = meta.nextInt(8);
            long planSeed = 100_000L + meta.nextInt(1_000_000);
            String fixture = "CTIM:" + (700 + meta.nextInt(300));
            int minute = 1 + meta.nextInt(120);

            List<PlayerSnapshot> ordered = roster();
            List<PlayerSnapshot> shuffled = new ArrayList<>(ordered);
            Collections.shuffle(shuffled, meta);

            MatchMomentSpec a = build(fixture, slot, planSeed, minute, period, phase, outcome, assist, ordered);
            MatchMomentSpec b = build(fixture, slot, planSeed, minute, period, phase, outcome, assist, shuffled);

            AnimationReplay ra = director.direct(a).replay();
            AnimationReplay rb = director.direct(b).replay();
            assertTrue(validator.validate(ra, a).isEmpty(), "scenario " + i);
            assertEquals(ra.fingerprint(), rb.fingerprint(), "order variance in scenario " + i);
            assertEquals(ra, rb);
            scenarios++;
        }
        assertTrue(scenarios >= 200, "swept " + scenarios);
    }

    private static MatchMomentSpec build(String fixture, int slot, long planSeed, int minute, MatchPeriod period,
                                         AnimationPhase phase, AnimationOutcome outcome, Long assist,
                                         List<PlayerSnapshot> players) {
        return new MatchMomentSpec(fixture, slot, planSeed, AnimationDirector.CURRENT_GENERATOR_VERSION,
                minute, 2, period, HOME, AWAY, HOME, phase, outcome, SCORER, assist, players, null);
    }
}
