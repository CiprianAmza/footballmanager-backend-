package com.footballmanagergamesimulator.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Shared finishing places for teams eliminated in the same cup stage. */
final class CupFinishingPositions {
    private CupFinishingPositions() {}

    static Map<Long, Integer> calculate(Set<Long> participants, Map<Long, Long> furthestRound,
                                         Long winnerId, Long runnerUpId) {
        Map<Long, Integer> places = new HashMap<>();
        for (long teamId : participants) {
            if (winnerId != null && teamId == winnerId) {
                places.put(teamId, 1);
            } else if (runnerUpId != null && teamId == runnerUpId) {
                places.put(teamId, 2);
            } else {
                long reached = furthestRound.getOrDefault(teamId, 0L);
                long ahead = participants.stream()
                        .filter(other -> furthestRound.getOrDefault(other, 0L) > reached)
                        .count();
                // Until a later stage is populated, this team may still advance.
                if (ahead > 0) places.put(teamId, Math.toIntExact(ahead + 1));
            }
        }
        return places;
    }
}
