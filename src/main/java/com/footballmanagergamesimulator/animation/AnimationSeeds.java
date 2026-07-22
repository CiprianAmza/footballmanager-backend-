package com.footballmanagergamesimulator.animation;

/**
 * Deterministic seed derivation for the animation engine. No wall clock, no
 * {@code Math.random}, no global state: the same canonical identity always
 * yields the same seed, on any JVM, after any restart.
 */
public final class AnimationSeeds {

    private AnimationSeeds() {
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** Salt separating the pattern-selection RNG stream from the pattern/compiler stream. */
    static final long SELECTION_SALT = 0x9e3779b97f4a7c15L;

    /** Seed = planSeed ⊕ fixtureKey ⊕ slotIndex ⊕ generatorVersion, well mixed. */
    public static long derive(long planSeed, String fixtureKey, int slotIndex, int generatorVersion) {
        long h = fnv1a64(fixtureKey);
        h = mix(h ^ planSeed);
        h = mix(h + 0x632be59bd9b4e019L * (slotIndex + 1L));
        h = mix(h + 0xbf58476d1ce4e5b9L * (generatorVersion + 1L));
        return h;
    }

    static long fnv1a64(String s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** SplitMix64 finalizer. */
    static long mix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
