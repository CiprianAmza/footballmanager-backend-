package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PlayPatternLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.ASSISTER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style sweep: EVERY pattern × every outcome it supports × several
 * seeds must produce a replay with zero physics violations — no teleports,
 * speed caps respected, ball possession/flight coherent, outcome geometry
 * correct, only snapshot players involved.
 */
class AnimationPhysicsInvariantsTest {

    private final FrameCompiler compiler = new FrameCompiler();
    private final AnimationPhysicsValidator validator = new AnimationPhysicsValidator();
    private final AnimationDirector director = new AnimationDirector();

    @Test
    void everyPatternHonoursEveryInvariantForEverySupportedOutcome() {
        List<PlayPattern> all = new ArrayList<>(PlayPatternLibrary.standard());
        all.add(PlayPatternLibrary.fallback());

        int combinationsChecked = 0;
        for (PlayPattern pattern : all) {
            for (AnimationPhase phase : AnimationPhase.values()) {
                for (AnimationOutcome outcome : AnimationOutcome.values()) {
                    for (Long assister : new Long[]{ASSISTER_ID, null}) {
                        for (int slot = 0; slot < 3; slot++) {
                            MatchMomentSpec s = spec(phase, outcome, slot, 40, assister);
                            if (!pattern.supports(s)) continue;
                            long seed = AnimationSeeds.derive(s.planSeed(), s.fixtureKey(),
                                    s.slotIndex(), s.generatorVersion());
                            Random rng = new Random(seed);
                            AnimationReplay replay =
                                    compiler.compile(s, pattern.choreograph(s, rng), rng);
                            List<String> violations = validator.validate(replay, s);
                            assertTrue(violations.isEmpty(),
                                    pattern.id() + "/" + phase + "/" + outcome
                                            + "/assist=" + (assister != null) + "/slot=" + slot
                                            + " violations: " + violations);
                            combinationsChecked++;
                        }
                    }
                }
            }
        }
        assertTrue(combinationsChecked > 100, "sweep actually ran: " + combinationsChecked);
    }

    @Test
    void patternsNeverChangeTheCanonicalOutcome() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                MatchMomentSpec s = spec(phase, outcome, 2, 70, null);
                AnimationReplay replay = director.direct(s).replay();
                assertEquals(outcome, replay.outcome());
                assertEquals(outcome.name(),
                        replay.events().get(replay.events().size() - 1).type());
                assertEquals(s.scorerId(), replay.scorerId());
                assertEquals(s.minute(), replay.minute());
                assertEquals(s.scoringTeamId(), replay.scoringTeamId());
            }
        }
    }

    @Test
    void directedAnimationsAlwaysValidate() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                for (int slot = 0; slot < 6; slot++) {
                    MatchMomentSpec s = spec(phase, outcome, slot, 15 + slot * 14,
                            slot % 2 == 0 ? ASSISTER_ID : null);
                    AnimationReplay replay = director.direct(s).replay();
                    List<String> violations = validator.validate(replay, s);
                    assertTrue(violations.isEmpty(),
                            phase + "/" + outcome + "/slot=" + slot + ": " + violations);
                }
            }
        }
    }

    @Test
    void noPlayerEverExceedsMaxSpeedNorTeleports() {
        MatchMomentSpec s = spec();
        AnimationReplay replay = director.direct(s).replay();
        List<ReplayFrame> frames = replay.frames();
        for (int f = 1; f < frames.size(); f++) {
            for (int i = 0; i < replay.players().size(); i++) {
                double[] p = frames.get(f).positions().get(i);
                double[] q = frames.get(f - 1).positions().get(i);
                double step = Math.hypot(p[0] - q[0], p[1] - q[1]);
                assertTrue(step <= AnimationPhysics.MAX_PLAYER_STEP + 0.15,
                        "frame " + f + " player " + i + " moved " + step);
            }
        }
    }

    @Test
    void noPlayerEverExceedsMaxAcceleration() {
        AnimationReplay replay = director.direct(spec()).replay();
        for (int f = 2; f < replay.frames().size(); f++) {
            for (int i = 0; i < replay.players().size(); i++) {
                double[] before = replay.frames().get(f - 2).positions().get(i);
                double[] previous = replay.frames().get(f - 1).positions().get(i);
                double[] current = replay.frames().get(f).positions().get(i);
                double previousVx = previous[0] - before[0];
                double previousVy = previous[1] - before[1];
                double currentVx = current[0] - previous[0];
                double currentVy = current[1] - previous[1];
                double acceleration = Math.hypot(
                        currentVx - previousVx, currentVy - previousVy);
                assertTrue(acceleration <= AnimationPhysics.MAX_PLAYER_ACCEL + 0.30,
                        "frame " + f + " player " + i + " accelerated " + acceleration);
            }
        }
    }

    @Test
    void stricterMotionLimitsAreConfigurableAndEnforced() {
        AnimationMotionLimits strict = new AnimationMotionLimits(0.60, 0.25);
        AnimationDirector strictDirector = new AnimationDirector(strict);
        AnimationPhysicsValidator strictValidator = new AnimationPhysicsValidator(strict);
        MatchMomentSpec s = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                5, 70, ASSISTER_ID);
        AnimationReplay replay = strictDirector.direct(s).replay();
        assertTrue(strictValidator.validate(replay, s).isEmpty());
    }
}
