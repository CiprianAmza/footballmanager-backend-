package com.footballmanagergamesimulator.animation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rough performance envelope — loose bounds, mainly to catch regressions. */
class AnimationPerformanceTest {

    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    @Test
    void generationTimeAndPayloadSizesAreReasonable() throws Exception {
        // Warm up JIT before timing.
        for (int i = 0; i < 5; i++) {
            director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, i, 30, null));
        }

        int runs = 20;
        long start = System.nanoTime();
        AnimationDirector.DirectedAnimation last = null;
        for (int i = 0; i < runs; i++) {
            last = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                    i, 10 + i * 4, i % 2 == 0 ? AnimationTestFixtures.ASSISTER_ID : null));
        }
        double avgMs = (System.nanoTime() - start) / 1_000_000.0 / runs;

        int recipeBytes = codec.toJson(last.recipe()).getBytes().length;
        int replayBytes = new ObjectMapper().writeValueAsString(last.replay()).getBytes().length;

        System.out.printf("animation v2: avg generation %.2f ms, recipe %d bytes, replay %d bytes%n",
                avgMs, recipeBytes, replayBytes);

        assertTrue(avgMs < 50, "generation too slow: " + avgMs + " ms");
        assertTrue(recipeBytes < 10_000, "recipe unexpectedly large: " + recipeBytes);
        assertTrue(replayBytes < 500_000, "replay payload unexpectedly large: " + replayBytes);
    }

    @Test
    void aSixGoalMatchStaysCheap() {
        long start = System.nanoTime();
        AnimationQueue queue = new AnimationQueue();
        for (int slot = 0; slot < 6; slot++) {
            queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                    slot, 10 + slot * 13, null)).replay());
        }
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("animation v2: 6-goal match generated in %.2f ms%n", totalMs);
        assertTrue(queue.size() == 6 && totalMs < 1_000, "6 goals in " + totalMs + " ms");
    }
}
