package com.footballmanagergamesimulator.animation;

/** Stable seed derivation; no runtime/environment input. */
public final class AnimationSeed {

    static final long SELECTION_SALT = 0x517cc1b727220a95L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private AnimationSeed() {
    }

    public static long derive(long planSeed, String fixtureKey, int slotIndex, int generatorVersion) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < fixtureKey.length(); i++) {
            hash ^= fixtureKey.charAt(i);
            hash *= FNV_PRIME;
        }
        hash = mix(hash ^ planSeed);
        hash = mix(hash ^ Integer.toUnsignedLong(slotIndex));
        return mix(hash ^ (Integer.toUnsignedLong(generatorVersion) << 32));
    }

    static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
