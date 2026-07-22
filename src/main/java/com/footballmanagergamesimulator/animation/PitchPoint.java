package com.footballmanagergamesimulator.animation;

/** Immutable 2-D pitch coordinate. */
public record PitchPoint(double x, double y) {

    /**
     * Serialized coordinate precision. Finer than one decimal so that the
     * rounding error added to a step or an acceleration second-difference stays
     * far below the configured physical limits, letting the validator reject any
     * frame over the limit with no tolerance above the profile.
     */
    public static final double PRECISION = 0.01;
    /** Upper bound on the distance a single rounded endpoint can move. */
    public static final double ROUNDING_HALF_STEP = Math.hypot(PRECISION / 2, PRECISION / 2);

    public PitchPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("pitch coordinates must be finite");
        }
    }

    public double distanceTo(PitchPoint other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    public PitchPoint mirrorX() {
        return new PitchPoint(round(100 - x), y);
    }

    public PitchPoint rounded() {
        return new PitchPoint(round(x), round(y));
    }

    private static double round(double value) {
        return Math.round(value / PRECISION) * PRECISION;
    }
}
