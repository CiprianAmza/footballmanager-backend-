package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class MatchLineupRatingView {

    private long homeTeamId;
    private String homeTeamName;
    private long awayTeamId;
    private String awayTeamName;
    private String homeFormation;
    private String awayFormation;

    private List<PlayerLine> homeLineup;
    private List<PlayerLine> awayLineup;

    @Data
    public static class PlayerLine {
        private long playerId;
        private String playerName;
        private String position;
        private int positionIndex;
        private String formation;
        private String role;
        private String duty;
        private boolean substitute;
        private double rating;
        private double performanceRating;
        private int goals;
        private int assists;
        private int age;
        private long nationId;
        private String nationName;
        private int baseFaceId;
        private int skinTone;
        private int hairStyle;
        private int hairColor;
        private int eyeColor;
        private int faceShape;
        private int noseShape;
        private int eyeShape;
        private int mouthShape;
        private int browShape;
        private String species;
    }
}
