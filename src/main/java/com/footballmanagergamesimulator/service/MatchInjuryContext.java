package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.MatchEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The match a post-match injury roll follows. The roll covers the whole squad; a player
 * who took part in this match gets an "injury" {@link MatchEvent} on it, placed at the last
 * minute he was on the pitch (the roll has no minute of its own). Unused substitutes and
 * the rest of the squad can still be injured by the roll, but get no match event.
 */
public record MatchInjuryContext(long competitionId, int seasonNumber, int roundNumber,
                                 long team1Id, long team2Id, Map<Long, Integer> lastMinuteOnPitch) {

    public MatchInjuryContext {
        lastMinuteOnPitch = Map.copyOf(lastMinuteOnPitch);
    }

    /** Both sides' canonical lineups; {@code duration} is 90, or 120 after extra time. */
    public static MatchInjuryContext of(long competitionId, int seasonNumber, int roundNumber,
                                        long team1Id, long team2Id,
                                        Lineup homeLineup, Lineup awayLineup, int duration) {
        Map<Long, Integer> lastMinute = new HashMap<>();
        for (Lineup lineup : new Lineup[]{homeLineup, awayLineup}) {
            if (lineup == null) continue;
            for (Lineup.Appearance appearance : lineup.appearances()) {
                int exit = appearance.exitMinute() != null ? appearance.exitMinute() : duration;
                lastMinute.put(appearance.playerId(), Math.max(1, exit));
            }
        }
        return new MatchInjuryContext(competitionId, seasonNumber, roundNumber, team1Id, team2Id, lastMinute);
    }

    /** The injury as an event of this match, or null when the player did not take part. */
    public MatchEvent eventFor(long teamId, Human player, Injury injury) {
        if (player == null || injury == null) return null;
        Integer minute = lastMinuteOnPitch.get(player.getId());
        if (minute == null) return null;
        MatchEvent event = new MatchEvent();
        event.setCompetitionId(competitionId);
        event.setSeasonNumber(seasonNumber);
        event.setRoundNumber(roundNumber);
        event.setTeamId1(team1Id);
        event.setTeamId2(team2Id);
        event.setMinute(minute);
        event.setEventType("injury");
        event.setPlayerId(player.getId());
        event.setPlayerName(player.getName());
        event.setTeamId(teamId);
        event.setDetails(injury.getSeverity() == null
                ? injury.getInjuryType()
                : injury.getInjuryType() + " (" + injury.getSeverity() + ")");
        return event;
    }
}
