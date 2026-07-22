package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckpointRandomTest {

    @Test
    void restoredStateContinuesWithExactlyTheSameSequence() {
        CheckpointRandom original = new CheckpointRandom(987654321L);
        for (int i = 0; i < 37; i++) {
            original.nextInt(10_000);
            original.nextDouble();
        }

        long checkpoint = original.checkpointState();
        CheckpointRandom restored = new CheckpointRandom(0L);
        restored.restoreCheckpointState(checkpoint);

        for (int i = 0; i < 100; i++) {
            assertEquals(original.nextInt(), restored.nextInt());
            assertEquals(original.nextDouble(), restored.nextDouble());
            assertEquals(original.nextBoolean(), restored.nextBoolean());
        }
    }
}
