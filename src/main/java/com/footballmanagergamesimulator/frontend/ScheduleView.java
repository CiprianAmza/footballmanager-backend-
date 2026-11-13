package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScheduleView {

    private String opponentTeam;
    private String homeOrAway;
    private String competitionName;
    private String score;
    private String date;
    private String homeTeamAbbr;   // first 3 letters of home team
    private String awayTeamAbbr;   // first 3 letters of away team

    // Fields needed for match event lookup
    private long competitionId;
    private int seasonNumber;
    private int roundNumber;
    private long teamId1; // home team id
    private long teamId2; // away team id
    private int day; // calendar day for sorting
    private Long winnerTeamId;
    private String decidedBy;

    // ---- Club Schedule page (additive; every field above keeps its old meaning) ----

    private long opponentTeamId;
    /** "played" or "upcoming", same vocabulary as /match/calendar. */
    private String status;
    /**
     * W/D/L for this match from the club's goals. A level match settled in it by
     * extra time or a shootout takes winnerTeamId (old saves keep extra-time goals
     * out of the score). Legs are the leg's own result; who went through stays in
     * winnerTeamId/decidedBy.
     * Null while upcoming.
     */
    private String resultOutcome;
    /** Club-perspective goals; null while upcoming or when the stored score is unreadable. */
    private Integer teamGoals;
    private Integer opponentGoals;
    /** "Matchday 5", "Quarter-Final", "Group Stage · Matchday 2", "Final", friendly stage. */
    private String roundLabel;
    /** "League", "Cup", "Continental" or "Friendly"; null when the competition is unknown. */
    private String competitionType;
    /** 0 = single match, 1 = first leg, 2 = second leg. */
    private int legNumber;
    /** Shared by both legs of a tie; 0 for single matches (see unknownFields for old saves). */
    private long tieId;
    /** Tie aggregate after leg 2, aligned with teamId1/teamId2 (stored, else summed from both legs). */
    private Integer aggregateTeam1Score;
    private Integer aggregateTeam2Score;
    /** Shootout score aligned with teamId1/teamId2; null when there was no shootout. */
    private Integer penaltyTeam1Score;
    private Integer penaltyTeam2Score;
    /** True when /match/report/{competitionId}/{seasonNumber}/{roundNumber}/{teamId1}/{teamId2} has this match. */
    private boolean reportAvailable;
    /** Home side's stadium (current name), or the friendly's own venue. */
    private String venueName;
    /** Names of fields above whose value the save does not hold; render them as unknown. */
    private List<String> unknownFields = new ArrayList<>();
}
