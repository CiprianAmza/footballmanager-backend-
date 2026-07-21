package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.model.MatchEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives per-player goal and assist tallies from the canonical {@link MatchEvent}
 * timeline. This is the single source for scorer statistics — the leaderboard,
 * player pages and records project from here instead of running their own
 * independent goal distribution. Shootout penalties are never {@code MatchEvent}
 * goals, so they are naturally excluded.
 */
public final class MatchEventProjection {

    private MatchEventProjection() {}

    /** Goals + assists for one player in one match. */
    public static final class Tally {
        public final long playerId;
        public int goals;
        public int assists;
        Tally(long playerId) { this.playerId = playerId; }
    }

    /** Aggregate goal/assist events by player. Order of first appearance preserved. */
    public static Map<Long, Tally> aggregate(Iterable<MatchEvent> events) {
        Map<Long, Tally> byPlayer = new LinkedHashMap<>();
        for (MatchEvent e : events) {
            String type = e.getEventType();
            if (!"goal".equals(type) && !"assist".equals(type)) continue;
            Tally t = byPlayer.computeIfAbsent(e.getPlayerId(), Tally::new);
            if ("goal".equals(type)) t.goals++;
            else t.assists++;
        }
        return byPlayer;
    }
}
