package com.footballmanagergamesimulator.engine.sensitivity;

/**
 * One cell of a {@link SensitivityMatrix}: how the win-rate-A of a single
 * invariant changes when a single parameter is swept from {@code min} to
 * {@code max} of its declared range.
 *
 * <p>{@code ppShift} is in <b>percentage points</b> (0.10 means +10 pp).
 * Positive means raising the parameter raises A's win rate; negative means
 * the opposite. Absolute magnitude is what matters when ranking impact.
 *
 * @param parameterName   the swept knob, e.g. {@code "power.ratioExponent"}
 * @param invariantName   the invariant whose rate we measured
 * @param rateAtMin       win-rate-A when parameter = min of range
 * @param rateAtMax       win-rate-A when parameter = max of range
 * @param ppShift         {@code rateAtMax - rateAtMin}, in percentage points
 */
public record SensitivityResult(
        String parameterName,
        String invariantName,
        double rateAtMin,
        double rateAtMax,
        double ppShift) {

    /** Absolute magnitude — used for ranking. */
    public double magnitude() {
        return Math.abs(ppShift);
    }
}
