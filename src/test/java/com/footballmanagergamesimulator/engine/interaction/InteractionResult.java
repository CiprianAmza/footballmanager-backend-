package com.footballmanagergamesimulator.engine.interaction;

/**
 * One cell of an {@link InteractionMatrix}: Sobol indices for one parameter
 * acting on one invariant's win-rate-A.
 *
 * <p>Interpretation primer:
 * <ul>
 *   <li>{@code s1 ≈ 0, st ≈ 0} — parameter has no effect (inert)</li>
 *   <li>{@code s1 ≈ st &gt; 0} — pure main effect; the parameter acts
 *       independently of others</li>
 *   <li>{@code st &gt; s1} (gap is "interaction") — parameter's effect changes
 *       depending on other parameters; can't tune it in isolation</li>
 *   <li>{@code s1 &lt; 0} — small-sample noise; treat as 0</li>
 * </ul>
 *
 * @param parameterName  the swept knob
 * @param invariantName  the invariant whose win-rate-A was measured
 * @param s1             first-order index (fraction of variance from this
 *                       parameter alone)
 * @param st             total-order index (s1 + all interactions involving
 *                       this parameter)
 */
public record InteractionResult(
        String parameterName,
        String invariantName,
        double s1,
        double st) {

    /** How much of this parameter's effect comes from interactions with others. */
    public double interactionStrength() {
        return Math.max(0.0, stClamped() - s1Clamped());
    }

    /** Clamped first-order (negative noise → 0). */
    public double s1Clamped() {
        return Math.max(0.0, s1);
    }

    /**
     * Clamped total-order. Enforces the theoretical constraint
     * {@code ST_i ≥ S1_i}: when small-sample noise drives raw ST below S1,
     * we lift it back up. This preserves "interaction strength = ST − S1 ≥ 0"
     * in the report so a noisy cell can't look like "interactions go
     * negative" (which is meaningless).
     */
    public double stClamped() {
        return Math.max(s1Clamped(), Math.max(0.0, st));
    }
}
