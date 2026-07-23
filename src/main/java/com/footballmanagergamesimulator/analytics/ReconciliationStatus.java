package com.footballmanagergamesimulator.analytics;

/**
 * Machine-checkable parity state between the canonical source (a committed
 * {@code MatchPlan}) and the existing aggregates/result for the same fixture.
 *
 * <p>The reconciliation gate only reads and compares already-persisted goal
 * totals — it never recomputes a score, resamples events or fabricates missing
 * facts. When no committed plan exists (legacy or estimate-only fixtures) the
 * status is {@link #NOT_APPLICABLE}: there is no canonical truth to reconcile
 * against, and that is reported honestly rather than asserted as agreement.
 */
public enum ReconciliationStatus {
    /** A committed canonical plan exists and every present source agrees with it. */
    MATCH,
    /** A committed canonical plan exists but at least one present source disagrees. */
    MISMATCH,
    /** No committed canonical plan for this fixture; nothing to reconcile (legacy/estimate). */
    NOT_APPLICABLE,
    /** A plan exists but has not reached a terminal (COMPLETED/COMMITTED) state yet. */
    PENDING
}
