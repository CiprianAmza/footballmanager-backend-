package com.footballmanagergamesimulator.model;

/**
 * Lifecycle of a Press Conference V2 session.
 *
 * <ul>
 *   <li>{@code PENDING} — created and persisted, no question answered yet.</li>
 *   <li>{@code IN_PROGRESS} — at least one question answered, not all.</li>
 *   <li>{@code COMPLETED} — all questions answered (or explicitly completed);
 *       immutable from this point.</li>
 *   <li>{@code DELEGATED} — handed to the assistant manager, who auto-answered
 *       deterministically; immutable.</li>
 * </ul>
 *
 * Both {@code COMPLETED} and {@code DELEGATED} are terminal and immutable.
 */
public enum PressConferenceStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    DELEGATED;

    public boolean isTerminal() {
        return this == COMPLETED || this == DELEGATED;
    }
}
