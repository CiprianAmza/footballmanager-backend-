package com.footballmanagergamesimulator.engine.interaction;

/**
 * Pure second-order Sobol index S_ij for one parameter pair on one
 * invariant. Means: "fraction of variance in this invariant's output
 * caused <b>specifically</b> by the joint interaction of {@code paramA} and
 * {@code paramB}, after their individual main effects are removed."
 *
 * <p>By construction {@code s2 ∈ [0, 1 − S1_A − S1_B]} in the noise-free
 * limit. The estimator clamps to {@code ≥ 0}; values close to 0 mean "no
 * dedicated pair interaction" — the params either don't interact at all,
 * or only via higher-order effects (3+ way).
 *
 * <p>Canonical ordering: {@code paramA} comes before {@code paramB} in the
 * {@code ParameterSpace}'s parameter list. Use this consistently when
 * looking up cells.
 *
 * @param invariantName the invariant whose variance was decomposed
 * @param paramA        first parameter of the pair
 * @param paramB        second parameter of the pair
 * @param s2            pure-pair Sobol index, clamped to ≥ 0
 */
public record PairInteractionResult(
        String invariantName,
        String paramA,
        String paramB,
        double s2) {

    /** Identity used for ranking — high score = strong interaction. */
    public String pairKey() {
        return paramA + " × " + paramB;
    }
}
