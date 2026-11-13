package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Goalscorers of one played match, keyed like /match/report. */
@Data
public class MatchGoalscorersView {

    private long competitionId;
    private int seasonNumber;
    private int roundNumber;
    private long teamId1; // home team id
    private long teamId2; // away team id
    private List<Goal> goals = new ArrayList<>();
    /** "minute"/"penalty" when only per-player goal counts exist; "goals" when the list is incomplete. */
    private List<String> unknownFields = new ArrayList<>();

    @Data
    public static class Goal {
        private long playerId;
        private String playerName;
        private long teamId;
        private Integer minute;
        private Boolean penalty;
    }
}
