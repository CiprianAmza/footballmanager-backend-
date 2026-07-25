package com.footballmanagergamesimulator.compartment.effects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure fail-closed validation for canonical match-effect projections. */
public final class CanonicalMatchStatsValidator {
    private CanonicalMatchStatsValidator() {}

    public static void validate(CanonicalMatchEffectsInput input) {
        Objects.requireNonNull(input, "input");
        int homeGoals = footballGoals(input.split().score90Home(), input.split().etHome());
        int awayGoals = footballGoals(input.split().score90Away(), input.split().etAway());
        Map<Long, Integer> goalsByTeam = new TreeMap<>();
        Map<Integer, List<CanonicalMatchEffectEvent>> bySlot = new TreeMap<>();
        List<CanonicalMatchEffectEvent> events = input.events();
        for (int i = 0; i < events.size(); i++) {
            CanonicalMatchEffectEvent event = events.get(i);
            if (event.teamId() != input.homeTeamId() && event.teamId() != input.awayTeamId()) {
                throw new IllegalArgumentException("event belongs to an unknown team");
            }
            if (i > 0 && CanonicalMatchEffectsInput.eventOrder().compare(events.get(i - 1), event) > 0) {
                throw new IllegalArgumentException("events are not in canonical order");
            }
            bySlot.computeIfAbsent(event.slotIndex(), ignored -> new ArrayList<>()).add(event);
            if ("GOAL".equals(event.eventType())) {
                goalsByTeam.merge(event.teamId(), 1, Integer::sum);
            }
        }
        if (goalsByTeam.getOrDefault(input.homeTeamId(), 0) != homeGoals
                || goalsByTeam.getOrDefault(input.awayTeamId(), 0) != awayGoals) {
            throw new IllegalArgumentException("goal events do not match football score");
        }
        for (List<CanonicalMatchEffectEvent> slotEvents : bySlot.values()) {
            CanonicalMatchEffectEvent goal = null;
            CanonicalMatchEffectEvent assist = null;
            for (CanonicalMatchEffectEvent event : slotEvents) {
                if ("GOAL".equals(event.eventType())) {
                    if (goal != null) throw new IllegalArgumentException("duplicate GOAL in slot");
                    goal = event;
                } else {
                    if (assist != null) throw new IllegalArgumentException("duplicate ASSIST in slot");
                    assist = event;
                }
            }
            if (goal == null && assist != null) {
                throw new IllegalArgumentException("ASSIST cannot exist without GOAL");
            }
            if (goal != null && assist != null) {
                if (goal.teamId() != assist.teamId() || goal.minute() != assist.minute()) {
                    throw new IllegalArgumentException("GOAL and ASSIST must share team and minute");
                }
                if (goal.playerId() == assist.playerId()) {
                    throw new IllegalArgumentException("scorer and assister must differ");
                }
            }
        }
    }

    public static int footballGoals(int score90, int extraTime) {
        long total = (long) score90 + Math.max(0, extraTime);
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("football goal count overflows int");
        return (int) total;
    }
}
