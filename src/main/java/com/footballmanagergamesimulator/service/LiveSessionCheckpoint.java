package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.model.MatchEvent;

import java.util.List;
import java.util.Map;

/** Serializable state required to resume a canonical live match without changing either
 * its visible statistics or the deferred non-goal events written at final commit. */
public record LiveSessionCheckpoint(
        LiveMatchData state,
        List<MatchEvent> pendingEvents,
        List<PlayerState> players,
        Map<Long, String> fieldedPositions,
        int homePossessionMinutes,
        int homeLastSubMinute,
        int awayLastSubMinute,
        List<ManualSubstitutionState> manualSubstitutions
) {
    /** Exact engine values.  The public DTO intentionally rounds stamina for display, which is
     * not precise enough for a deterministic continuation after a backend restart. */
    public record PlayerState(
            long playerId,
            double currentStamina,
            int minutesPlayed,
            boolean onPitch,
            int yellowCardMinute,
            int redCardMinute
    ) {}

    public record ManualSubstitutionState(
            long teamId,
            long playerOutId,
            long playerInId,
            int minute
    ) {}
}
