package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class MatchSummaryView {

    private String homeTeamName;
    private String awayTeamName;
    private long homeTeamId;
    private long awayTeamId;
    private String score;

    // Goal scorers with minutes
    private List<GoalDetail> goals;

    // Team ratings
    private List<PlayerRating> homePlayerRatings;
    private List<PlayerRating> awayPlayerRatings;

    // Man of the match
    private String manOfTheMatchName;
    private long manOfTheMatchPlayerId;
    private double manOfTheMatchRating;
    private long manOfTheMatchTeamId;

    // Possession estimate
    private int homePossession;
    private int awayPossession;

    @Data
    public static class GoalDetail {
        private String playerName;
        private long playerId;
        private int minute;
        private String details;
        private long teamId;
        private String teamName;
    }

    @Data
    public static class PlayerRating {
        private String playerName;
        private long playerId;
        private String position;
        private double rating;
        private int goals;
        private int assists;
    }
}
