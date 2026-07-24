package com.footballmanagergamesimulator.compartment.effects;

import java.util.Locale;

/** Immutable, persistence-free projection of one canonical goal contribution. */
public record CanonicalMatchEffectEvent(int slotIndex, int minute, long teamId, long playerId,
                                        String eventType) {
    public CanonicalMatchEffectEvent {
        if (slotIndex < 0) throw new IllegalArgumentException("slotIndex must be non-negative");
        if (minute <= 0) throw new IllegalArgumentException("minute must be positive");
        if (teamId <= 0) throw new IllegalArgumentException("teamId must be positive");
        if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
        if (eventType == null) throw new IllegalArgumentException("eventType must not be null");
        eventType = eventType.trim().toUpperCase(Locale.ROOT);
        if (!"GOAL".equals(eventType) && !"ASSIST".equals(eventType)) {
            throw new IllegalArgumentException("eventType must be GOAL or ASSIST");
        }
    }
}
