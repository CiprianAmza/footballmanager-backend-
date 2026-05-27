package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class LiveMatchData {

    // Goal animations keyed by minute (only present when watchGoalHighlights is enabled)
    private Map<Integer, GoalAnimationData> goalAnimations;

    private long homeTeamId;
    private long awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String competitionName;
    private long competitionId;
    private int round;

    private List<LiveMatchMinute> timeline;

    // Final stats
    private int homeScore;
    private int awayScore;
    private int homePossession;
    private int awayPossession;
    private int homeShots;
    private int awayShots;
    private int homeShotsOnTarget;
    private int awayShotsOnTarget;
    private int homeCorners;
    private int awayCorners;
    private int homeFouls;
    private int awayFouls;
    private int homeYellowCards;
    private int awayYellowCards;
    private int homeRedCards;
    private int awayRedCards;
    private int homeOffsides;
    private int awayOffsides;

    // Added time per half, generated randomly per match (0-5 minutes each).
    // The frontend uses these to display minutes in "45+X" / "90+X" form
    // during the stoppage portions of each half.
    private int firstHalfStoppage;
    private int secondHalfStoppage;

    @Data
    public static class LiveMatchMinute {
        private int minute;
        private int homeScore;
        private int awayScore;
        private String eventType;
        private String commentary;
        private String playerName;
        private long playerId;
        private long teamId;
        private String teamName;
    }
}
