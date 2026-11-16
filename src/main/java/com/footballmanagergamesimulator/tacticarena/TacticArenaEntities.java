package com.footballmanagergamesimulator.tacticarena;

/**
 * Namespace holder for the Tactic Arena persistence model. The entities themselves are
 * top-level classes in this package: {@link TacticArenaTest}, {@link TacticArenaRun},
 * {@link TacticArenaRunResult} and {@link TacticArenaImage}.
 */
final class TacticArenaEntities {

    static final String QUEUED = "QUEUED";
    static final String PREPARING = "PREPARING";
    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";
    static final String CANCELLED = "CANCELLED";

    static final String KIND_CHOSEN = "CHOSEN";
    static final String KIND_TOP = "REFERENCE_TOP";
    static final String KIND_MEDIAN = "REFERENCE_MEDIAN";
    static final String KIND_BOTTOM = "REFERENCE_BOTTOM";
    static final String KIND_DIAMOND = "DIAMOND";

    static final String CATEGORY_GENERAL = "GENERAL";
    static final String CATEGORY_DIAMOND = "DIAMOND";

    private TacticArenaEntities() {
    }

    static boolean terminal(String status) {
        return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
