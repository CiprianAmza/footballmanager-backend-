package com.footballmanagergamesimulator.animation;

/**
 * Runtime-configurable player-motion limits used by both the frame compiler
 * and the invariant validator. Production uses {@link #defaults()}; focused
 * tests or a future configuration adapter can supply stricter values without
 * changing pattern code or canonical facts.
 */
public record AnimationMotionLimits(
        double maxPlayerStep,
        double maxPlayerAcceleration) {

    public AnimationMotionLimits {
        if (!Double.isFinite(maxPlayerStep) || maxPlayerStep <= 0) {
            throw new IllegalArgumentException("maxPlayerStep must be finite and positive");
        }
        if (!Double.isFinite(maxPlayerAcceleration) || maxPlayerAcceleration <= 0) {
            throw new IllegalArgumentException("maxPlayerAcceleration must be finite and positive");
        }
    }

    public static AnimationMotionLimits defaults() {
        return new AnimationMotionLimits(
                AnimationPhysics.MAX_PLAYER_STEP,
                AnimationPhysics.MAX_PLAYER_ACCEL);
    }
}
