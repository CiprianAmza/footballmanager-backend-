package com.footballmanagergamesimulator.animation;

/**
 * Physical constants of the v2 engine, shared by the compiler and the
 * validator. Frames run at ~30 fps; coordinates are 0–100 on both axes
 * (roughly 105 m × 68 m), so 1 unit/frame ≈ 1 m per 1/30 s.
 */
public final class AnimationPhysics {

    private AnimationPhysics() {
    }

    /** Frames are indexed 0..TOTAL_FRAMES inclusive (151 frames, ~5 s at 30 fps). */
    public static final int TOTAL_FRAMES = 150;

    /** Hard per-frame movement cap for a player (no teleports, ever). */
    public static final double MAX_PLAYER_STEP = 0.9;

    /** Per-frame velocity change cap for a player (smooth accelerations). */
    public static final double MAX_PLAYER_ACCEL = 0.45;

    /** Hard per-frame movement cap for the ball. */
    public static final double MAX_BALL_STEP = 4.0;

    /** Goal mouth on the goal line: the posts sit at y=44 and y=56. */
    public static final double GOAL_MOUTH_MIN_Y = 44;
    public static final double GOAL_MOUTH_MAX_Y = 56;

    /** GOAL shots aim inside the posts with a small inset. */
    public static final double GOAL_TARGET_MIN_Y = 45.5;
    public static final double GOAL_TARGET_MAX_Y = 54.5;

    /** A SAVE must end within this distance of the goalkeeper. */
    public static final double GK_REACH = 3.0;

    /** A BLOCKED shot must end within this distance of the blocking defender. */
    public static final double BLOCK_REACH = 2.0;

    /** Players stay inside the pitch with a small margin. */
    public static final double PLAYER_MIN_X = 0.5;
    public static final double PLAYER_MAX_X = 99.5;
    public static final double PLAYER_MIN_Y = 1.0;
    public static final double PLAYER_MAX_Y = 99.0;

    /** Ball/carrier coincidence tolerance after 0.1 rounding. */
    public static final double CARRY_EPSILON = 0.11;
}
