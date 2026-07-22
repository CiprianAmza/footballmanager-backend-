package com.footballmanagergamesimulator.animation;

/** Recipe-pinned, runtime-configurable physical limits. */
public record AnimationPhysicsProfile(
        double maxPlayerStep,
        double maxPlayerAcceleration,
        double maxBallStep) {

    /**
     * Smallest limits the engine can honour while still animating the full range
     * of canonical moments inside {@link FrameCompiler#TOTAL_FRAMES} frames. A
     * profile below any of these is physically impossible and is rejected at
     * construction so no generation attempt can throw for an accepted profile.
     */
    public static final double MIN_PLAYER_STEP = 0.3;
    public static final double MIN_PLAYER_ACCELERATION = 0.1;
    public static final double MIN_BALL_STEP = 1.0;

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
    }

    public static AnimationPhysicsProfile defaults() {
        return new AnimationPhysicsProfile(0.9, 0.45, 4.0);
    }

    /**
     * Largest player step the compiler may command so that, after coordinate
     * rounding, no serialized step exceeds {@link #maxPlayerStep}.
     */
    public double playerStepCap() {
        return headroom(maxPlayerStep, 2 * PitchPoint.ROUNDING_HALF_STEP);
    }

    /**
     * Largest player acceleration the compiler may command so that, after
     * rounding, no serialized second-difference exceeds {@link #maxPlayerAcceleration}.
     * A second difference touches three rounded points (weights 1, -2, 1).
     */
    public double playerAccelerationCap() {
        return headroom(maxPlayerAcceleration, 4 * PitchPoint.ROUNDING_HALF_STEP);
    }

    /** Largest ball step the compiler may command so rounding stays within {@link #maxBallStep}. */
    public double ballStepCap() {
        return headroom(maxBallStep, 2 * PitchPoint.ROUNDING_HALF_STEP);
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
