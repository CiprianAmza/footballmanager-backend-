package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class MatchLineupRatingView {

    private long homeTeamId;
    private String homeTeamName;
    private long awayTeamId;
    private String awayTeamName;

    private List<PlayerLine> homeLineup;
    private List<PlayerLine> awayLineup;

    @Data
    public static class PlayerLine {
        private long playerId;
        private String playerName;
        private String position;
        private double rating;
        private int age;
        private long nationId;
        private String nationName;
    }
}
