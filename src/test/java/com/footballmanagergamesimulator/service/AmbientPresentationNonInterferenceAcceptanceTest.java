package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.animation.AnimationSeed;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GATE 1 — presentation non-interference (ACCEPTANCE).
 *
 * <p><b>Expected to be RED until Faza 2 is implemented.</b> Excluded from the default
 * build by {@code @Tag("faza2-acceptance")}; run with {@code mvn test -Pfaza2-gates}.
 * These are executable specifications, not regressions — see AI_HANDOFF rev. 8 ask B.
 *
 * <p>Specifies: with ambient generation enabled versus disabled, the same fixture must
 * produce an identical score, ordered canonical slots, contributors, substitutions,
 * cards, statistics, canonical checkpoint fields and final committed rows — and the live
 * RNG checkpoint state must be untouched by compiling or serializing ambient frames.
 *
 * <p>The reproducibility baseline this comparison relies on is proven green today by
 * {@code LiveAmbientNonInterferenceCharacterizationTest}, which also pins the ONE known
 * counter-example to copy nowhere: the legacy animation budget spends a live RNG draw.
 */
@Tag("faza2-acceptance")
class AmbientPresentationNonInterferenceAcceptanceTest {

    private static final String GATE = "gate 1 (presentation non-interference)";

    @Test
    void ambientOnVersusOff_producesAnIdenticalCanonicalOutcome() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_COMPILER, GATE,
                "the backend compiler that turns each engine minute into a short ambient segment.");

        Faza2GateHarness off = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(off.engineConfig, false, GATE);
        LiveMatchSession offSession = off.start();
        LiveMatchData offData = offSession.advanceUntilAndSnapshot(offSession.getTotalMinutes());

        Faza2GateHarness on = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(on.engineConfig, true, GATE);
        LiveMatchSession onSession = on.start();
        LiveMatchData onData = onSession.advanceUntilAndSnapshot(onSession.getTotalMinutes());

        assertEquals(Faza2GateHarness.canonicalFingerprint(offData),
                Faza2GateHarness.canonicalFingerprint(onData),
                "ambient generation is presentation-only: score, ordered canonical slots, "
                        + "contributors, substitutions, cards and statistics must be identical");
        assertEquals(off.checkpointJson(), on.checkpointJson(),
                "the canonical checkpoint fields must be identical apart from ambient-only "
                        + "version/spec metadata — and that metadata must not perturb engine state");
        assertEquals(off.resolveCalls, on.resolveCalls,
                "the canonical slots resolve identically, in the same order");
        assertEquals(off.recipeRows.keySet(), on.recipeRows.keySet(),
                "ambient must not add rows to match_animation_recipe");
    }

    @Test
    void compilingAndSerializingAmbient_leavesTheLiveRngCheckpointStateUntouched() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_COMPILER, GATE,
                "ambient must be a pure projection using a NEW LOCAL RNG (rev. 6 answer 2).");

        Faza2GateHarness off = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(off.engineConfig, false, GATE);
        LiveMatchSession offSession = off.start();
        offSession.advanceUntilAndSnapshot(45);

        Faza2GateHarness on = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(on.engineConfig, true, GATE);
        LiveMatchSession onSession = on.start();
        onSession.advanceUntilAndSnapshot(45);

        Long offRng = Faza2GateHarness.liveRandomState(offSession);
        Long onRng = Faza2GateHarness.liveRandomState(onSession);
        assertNotNull(offRng);
        assertEquals(offRng, onRng,
                "the ambient compiler must never consume or mutate the session CheckpointRandom");
        assertEquals(offSession.currentMinute, onSession.currentMinute);
        assertEquals(offSession.getHomeScore(), onSession.getHomeScore());
        assertEquals(off.commitContext.get().getCheckpointRandomState(),
                on.commitContext.get().getCheckpointRandomState(),
                "the checkpointed RNG word is identical with ambient on");
    }

    @Test
    void ambientSeeds_areDomainSeparatedFromTheGoalAnimationSeedSpace() throws Exception {
        Method derive = Faza2ContractProbe.requireAmbientSeedDerive(GATE);

        // AnimationSeed.derive's third input is slotIndex; feeding a minute there without a
        // domain salt would accidentally correlate the two seed spaces (rev. 6 answer 2).
        for (int minute = 0; minute <= 90; minute += 15) {
            long ambient = (long) derive.invoke(null, 12345L, Faza2GateHarness.FIXTURE, minute, 1);
            long animation = AnimationSeed.derive(12345L, Faza2GateHarness.FIXTURE, minute, 1);
            assertNotEquals(animation, ambient,
                    "ambient and moment seed spaces must not collide at minute " + minute);
        }
        long first = (long) derive.invoke(null, 12345L, Faza2GateHarness.FIXTURE, 30, 1);
        long again = (long) derive.invoke(null, 12345L, Faza2GateHarness.FIXTURE, 30, 1);
        assertEquals(first, again, "the derivation is pure");
        assertNotEquals(first, (long) derive.invoke(null, 12345L, Faza2GateHarness.FIXTURE, 31, 1),
                "consecutive minutes get distinct seeds");
        assertNotEquals(first, (long) derive.invoke(null, 12345L, Faza2GateHarness.FIXTURE, 30, 2),
                "a generator version bump changes the seed, so old versions stay frozen");
    }
}
