package com.footballmanagergamesimulator.animation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationPerformanceTest {
    @Test void measuresGenerationAndPayloadEnvelope() throws Exception {
        AnimationDirector director = new AnimationDirector(); AnimationRecipeCodec codec = new AnimationRecipeCodec();
        for (int i = 0; i < 5; i++) director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, i, 30, null));
        int runs = 20; long started = System.nanoTime(); AnimationDirector.DirectedAnimation last = null;
        for (int i = 0; i < runs; i++) last = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, i, 10 + i * 4, i % 2 == 0 ? ASSISTER : null));
        double averageMs = (System.nanoTime() - started) / 1_000_000.0 / runs;
        int recipeBytes = codec.encode(last.recipe()).getBytes().length;
        int replayBytes = new ObjectMapper().writeValueAsString(last.replay()).getBytes().length;
        System.out.printf("animation v3: avg %.2f ms, recipe %d bytes, replay %d bytes%n", averageMs, recipeBytes, replayBytes);
        assertTrue(averageMs < 50); assertTrue(recipeBytes < 12_000); assertTrue(replayBytes < 500_000);
    }

    @Test void sixAnimationMatchStaysCheap() {
        AnimationDirector director = new AnimationDirector(); AnimationQueue queue = new AnimationQueue(); long started = System.nanoTime();
        for (int slot = 0; slot < 6; slot++) queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, slot, 10 + slot * 13, null)).replay());
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        System.out.printf("animation v3: six animations %.2f ms%n", millis);
        assertTrue(queue.size() == 6 && millis < 1_000);
    }
}
