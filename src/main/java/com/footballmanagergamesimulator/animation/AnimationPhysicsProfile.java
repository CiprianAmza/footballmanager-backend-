package com.footballmanagergamesimulator.animation;

/** Recipe-pinned, runtime-configurable physical limits. */
public record AnimationPhysicsProfile(
        double maxPlayerStep,
        double maxPlayerAcceleration,
        double maxBallStep) {

    public AnimationPhysicsProfile {
        requirePositive(maxPlayerStep, "maxPlayerStep");
        requirePositive(maxPlayerAcceleration, "maxPlayerAcceleration");
        requirePositive(maxBallStep, "maxBallStep");
    }

    public static AnimationPhysicsProfile defaults() {
        return new AnimationPhysicsProfile(0.9, 0.45, 4.0);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
