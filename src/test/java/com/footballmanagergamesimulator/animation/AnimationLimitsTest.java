package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Serialized frames must never exceed the configured physical limits — no tolerance above the profile. */
class AnimationLimitsTest {
    private static final AnimationPhysicsProfile[] PROFILES = {
            AnimationPhysicsProfile.defaults(),
            new AnimationPhysicsProfile(0.6, 0.25, 4.0),
            new AnimationPhysicsProfile(0.5, 0.2, 1.5),
            new AnimationPhysicsProfile(1.2, 0.6, 6.0)
    };

    @Test void noFrameEverExceedsTheConfiguredLimits() {
        for (AnimationPhysicsProfile profile : PROFILES) {
            AnimationDirector director = new AnimationDirector(profile);
            AnimationInvariantValidator validator = new AnimationInvariantValidator(profile);
            for (AnimationPhase phase : AnimationPhase.values())
                for (AnimationOutcome outcome : AnimationOutcome.values())
                    for (Long assist : new Long[]{ASSISTER, null})
                        for (int slot = 0; slot < 4; slot++) {
                            MatchMomentSpec spec = spec(phase, outcome, slot, 55, assist);
                            AnimationReplay replay = director.direct(spec).replay();
                            assertTrue(validator.validate(replay, spec).isEmpty(), phase + "/" + outcome);
                            assertWithinLimits(replay, profile);
                        }
        }
    }

    private static void assertWithinLimits(AnimationReplay replay, AnimationPhysicsProfile profile) {
        List<AnimationFrame> frames = replay.frames();
        for (int f = 1; f < frames.size(); f++) {
            double ballStep = frames.get(f).ball().distanceTo(frames.get(f - 1).ball());
            assertTrue(ballStep <= profile.maxBallStep(),
                    "ball step " + ballStep + " > " + profile.maxBallStep());
            int players = frames.get(f).positions().size();
            for (int p = 0; p < players; p++) {
                PitchPoint now = frames.get(f).positions().get(p);
                PitchPoint prev = frames.get(f - 1).positions().get(p);
                double step = now.distanceTo(prev);
                assertTrue(step <= profile.maxPlayerStep(),
                        "player step " + step + " > " + profile.maxPlayerStep());
                if (f >= 2) {
                    PitchPoint before = frames.get(f - 2).positions().get(p);
                    double ax = (now.x() - prev.x()) - (prev.x() - before.x());
                    double ay = (now.y() - prev.y()) - (prev.y() - before.y());
                    double accel = Math.hypot(ax, ay);
                    assertTrue(accel <= profile.maxPlayerAcceleration(),
                            "player accel " + accel + " > " + profile.maxPlayerAcceleration());
                }
            }
        }
    }
}
