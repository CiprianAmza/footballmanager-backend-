package com.footballmanagergamesimulator.animation;

/**
 * A presentation event inside the replay ("PASS", "SHOT", "GOAL", "SAVE",
 * "MISS", "BLOCKED"). Purely visual — the canonical outcome lives on the spec.
 *
 * @param toPlayerId 0 for non-pass events
 */
public record ReplayEvent(int frame, String type, long fromPlayerId, long toPlayerId) {
}
