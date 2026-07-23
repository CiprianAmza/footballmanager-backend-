package com.footballmanagergamesimulator.animation;

/** Canonical animation identity. */
public record AnimationKey(String fixtureKey, int slotIndex) implements Comparable<AnimationKey> {

    public AnimationKey {
        if (fixtureKey == null || fixtureKey.isBlank()) throw new IllegalArgumentException("fixtureKey is required");
        if (slotIndex < 0) throw new IllegalArgumentException("slotIndex must be non-negative");
    }

    @Override
    public int compareTo(AnimationKey other) {
        int fixture = fixtureKey.compareTo(other.fixtureKey);
        return fixture != 0 ? fixture : Integer.compare(slotIndex, other.slotIndex);
    }
}
