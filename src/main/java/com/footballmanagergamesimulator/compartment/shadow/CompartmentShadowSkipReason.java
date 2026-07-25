package com.footballmanagergamesimulator.compartment.shadow;

public enum CompartmentShadowSkipReason {
    FLAG_DISABLED,
    NON_AI_MATCH,
    ADMIN_FORCED_SCORE,
    TACTICAL_MODEL_DISABLED,
    MISSING_CANONICAL_TACTIC,
    INVALID_LINEUP_SIZE,
    DUPLICATE_PLAYER,
    MISSING_PLAYER_DATA,
    UNSUPPORTED_VENUE,
    EVALUATION_FAILED
}
