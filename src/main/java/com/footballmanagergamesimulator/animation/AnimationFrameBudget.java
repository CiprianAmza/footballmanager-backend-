package com.footballmanagergamesimulator.animation;

/**
 * Physically designed frame budget for the current generator. The number of frames scales with the
 * profile so that, at any accepted speed, a participant can start from their real formation anchor and
 * travel continuously to the phase points — even a deep goalkeeper acting as scorer or assister running
 * the length of the pitch — with the ball's flights fitting too. Slower profiles get proportionally more
 * frames; there is no frame-0 teleport and no per-profile special case.
 *
 * <p>The budget is a deterministic function of the profile (which the recipe pins), so a replay
 * regenerates with exactly the same frame count.
 */
public final class AnimationFrameBudget {
    /** Worst-case continuous player run: a goalkeeper anchor to the advanced finishing point. */
    private static final double WORST_RUN = 84;
    /** Worst-case ball travel once participants are advanced (short assist + finish). */
    private static final double WORST_BALL = 44;
    private static final int OVERHEAD = 32;
    private static final int MIN_FRAMES = 150;
    private static final int MAX_FRAMES = 600;
    /** Legacy version 1 is frozen at its original fixed length. */
    static final int LEGACY_FRAMES = 150;

    private AnimationFrameBudget() { }

    /** Number of animated frames (the replay holds this many + 1) for the current-generator profile. */
    public static int framesFor(AnimationPhysicsProfile profile) {
        double stepCap = profile.playerStepCap();
        double accelCap = profile.playerAccelerationCap();
        double ballCap = profile.ballStepCap();
        int run = (int) Math.ceil(WORST_RUN / (0.85 * stepCap)) + (int) Math.ceil(stepCap / accelCap);
        int ball = (int) Math.ceil(WORST_BALL / ballCap);
        return Math.max(MIN_FRAMES, Math.min(MAX_FRAMES, run + ball + OVERHEAD));
    }

    /** Frames for a replay of the given generator version (legacy version 1 is fixed). */
    public static int framesFor(int version, AnimationPhysicsProfile profile) {
        return version == 1 ? LEGACY_FRAMES : framesFor(profile);
    }
}
