package com.footballmanagergamesimulator.compartment.effects;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable input boundary for projecting a persisted canonical match decision. */
public record CanonicalMatchEffectsInput(MatchScoringDecision decision,
                                         KnockoutPlanSplit split,
                                         long homeTeamId,
                                         long awayTeamId,
                                         List<CanonicalMatchEffectEvent> events) {
    private static final Comparator<CanonicalMatchEffectEvent> EVENT_ORDER =
            Comparator.comparingInt(CanonicalMatchEffectEvent::slotIndex)
                    .thenComparingInt(event -> "GOAL".equals(event.eventType()) ? 0 : 1)
                    .thenComparingLong(CanonicalMatchEffectEvent::playerId);

    public CanonicalMatchEffectsInput {
        if (decision == null) throw new IllegalArgumentException("decision must not be null");
        if (split == null) throw new IllegalArgumentException("split must not be null");
        if (homeTeamId <= 0 || awayTeamId <= 0 || homeTeamId == awayTeamId) {
            throw new IllegalArgumentException("team IDs must be positive and distinct");
        }
        if (decision.fixtureKey() == null || decision.fixtureKey().isBlank()) {
            throw new IllegalArgumentException("decision fixtureKey must not be blank");
        }
        split.validateAgainst(decision);
        if (events == null) throw new IllegalArgumentException("events must not be null");
        List<CanonicalMatchEffectEvent> ordered = new ArrayList<>(events.size());
        for (CanonicalMatchEffectEvent event : events) {
            if (event == null) throw new IllegalArgumentException("events must not contain null");
            if (event.teamId() != homeTeamId && event.teamId() != awayTeamId) {
                throw new IllegalArgumentException("event belongs to an unknown team");
            }
            ordered.add(event);
        }
        ordered.sort(EVENT_ORDER);
        events = List.copyOf(ordered);
    }

    public static Comparator<CanonicalMatchEffectEvent> eventOrder() {
        return EVENT_ORDER;
    }
}
