package com.footballmanagergamesimulator.service;

import java.util.Random;

/**
 * {@link Random}-compatible generator whose exact internal state can be persisted with a
 * live-match checkpoint.  java.util.Random is serializable, but persisting a Java object blob
 * would couple saves to a JDK implementation.  This class deliberately implements the same
 * 48-bit LCG while exposing only the stable state value needed to resume the next draw.
 */
final class CheckpointRandom extends Random {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long state;

    CheckpointRandom(long seed) {
        super(0L);
        setSeed(seed);
    }

    @Override
    public synchronized void setSeed(long seed) {
        state = (seed ^ MULTIPLIER) & MASK;
    }

    @Override
    protected synchronized int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    synchronized long checkpointState() {
        return state;
    }

    synchronized void restoreCheckpointState(long checkpointState) {
        state = checkpointState & MASK;
    }
}
