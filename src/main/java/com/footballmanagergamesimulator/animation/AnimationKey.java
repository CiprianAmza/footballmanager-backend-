package com.footballmanagergamesimulator.animation;

/** Identity of one animation: the canonical event it renders. */
public record AnimationKey(String fixtureKey, int slotIndex) implements Comparable<AnimationKey> {

    public AnimationKey {
        if (fixtureKey == null || fixtureKey.isBlank()) {
            throw new IllegalArgumentException("fixtureKey is required");
        }
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be >= 0");
        }
    }

    public static AnimationKey of(MatchMomentSpec spec) {
        return new AnimationKey(spec.fixtureKey(), spec.slotIndex());
    }

    @Override
    public int compareTo(AnimationKey o) {
        int c = fixtureKey.compareTo(o.fixtureKey);
        return c != 0 ? c : Integer.compare(slotIndex, o.slotIndex);
    }
}
