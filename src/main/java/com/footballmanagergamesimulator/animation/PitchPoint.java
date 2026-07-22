package com.footballmanagergamesimulator.animation;

/** Immutable 2-D pitch coordinate. */
public record PitchPoint(double x, double y) {

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
        return Math.round(value * 10.0) / 10.0;
    }
}
