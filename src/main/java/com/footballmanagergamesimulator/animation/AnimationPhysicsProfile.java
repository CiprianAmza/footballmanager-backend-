package com.footballmanagergamesimulator.animation;

/** Recipe-pinned, runtime-configurable physical limits. */
public record AnimationPhysicsProfile(
        double maxPlayerStep,
        double maxPlayerAcceleration,
        double maxBallStep) {

    /**
     * Smallest limits the engine can honour. A profile below any of these is physically impossible and
     * is rejected at construction so no generation attempt can throw for an accepted profile. The frame
     * budget itself scales with the profile (see {@link AnimationFrameBudget}).
     */
    public static final double MIN_PLAYER_STEP = 0.3;
    public static final double MIN_PLAYER_ACCELERATION = 0.1;
    public static final double MIN_BALL_STEP = 1.0;
    /**
     * A player must be able to accelerate from rest to full stride within a bounded number of frames;
     * otherwise the ramp alone would dominate any budget. This bounds the step/acceleration ratio and
     * keeps {@code direct()} total across the whole accepted profile domain.
     */
    public static final double MAX_ACCELERATION_RAMP_FRAMES = 25.0;

    public AnimationPhysicsProfile {
        requirePositive(maxPlayerStep, "maxPlayerStep");
        requirePositive(maxPlayerAcceleration, "maxPlayerAcceleration");
        requirePositive(maxBallStep, "maxBallStep");
        requireFeasible(maxPlayerStep, MIN_PLAYER_STEP, "maxPlayerStep");
        requireFeasible(maxPlayerAcceleration, MIN_PLAYER_ACCELERATION, "maxPlayerAcceleration");
        requireFeasible(maxBallStep, MIN_BALL_STEP, "maxBallStep");
        // A carried or struck ball is never slower than the carrier.
        if (maxBallStep < maxPlayerStep)
            throw new IllegalArgumentException("maxBallStep " + maxBallStep
                    + " is physically impossible below maxPlayerStep " + maxPlayerStep);
        // The step/acceleration ratio must leave room to move within the frame budget.
        double rampFrames = playerStepCapFor(maxPlayerStep) / playerAccelerationCapFor(maxPlayerAcceleration);
        if (rampFrames > MAX_ACCELERATION_RAMP_FRAMES)
            throw new IllegalArgumentException("maxPlayerStep " + maxPlayerStep + " relative to acceleration "
                    + maxPlayerAcceleration + " needs " + Math.ceil(rampFrames)
                    + " frames to reach stride, over the budget of " + (int) MAX_ACCELERATION_RAMP_FRAMES);
    }

    public static AnimationPhysicsProfile defaults() {
        return new AnimationPhysicsProfile(0.9, 0.45, 4.0);
    }

    /**
     * Largest player step the compiler may command so that, after coordinate
     * rounding, no serialized step exceeds {@link #maxPlayerStep}.
     */
    public double playerStepCap() {
        return playerStepCapFor(maxPlayerStep);
    }

    /**
     * Largest player acceleration the compiler may command so that, after
     * rounding, no serialized second-difference exceeds {@link #maxPlayerAcceleration}.
     * A second difference touches three rounded points (weights 1, -2, 1).
     */
    public double playerAccelerationCap() {
        return playerAccelerationCapFor(maxPlayerAcceleration);
    }

    /** Largest ball step the compiler may command so rounding stays within {@link #maxBallStep}. */
    public double ballStepCap() {
        return headroom(maxBallStep, 2 * PitchPoint.ROUNDING_HALF_STEP);
    }

    private static double playerStepCapFor(double maxPlayerStep) {
        return headroom(maxPlayerStep, 2 * PitchPoint.ROUNDING_HALF_STEP);
    }

    private static double playerAccelerationCapFor(double maxPlayerAcceleration) {
        return headroom(maxPlayerAcceleration, 4 * PitchPoint.ROUNDING_HALF_STEP);
    }

    private static double headroom(double limit, double rounding) {
        double cap = limit - rounding - PitchPoint.PRECISION;
        return Math.max(limit * 0.5, cap);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireFeasible(double value, double minimum, String name) {
        if (value < minimum)
            throw new IllegalArgumentException(name + " " + value + " is physically impossible; minimum is " + minimum);
    }
}
