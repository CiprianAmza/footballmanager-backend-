package com.footballmanagergamesimulator.frontend;

import lombok.Data;

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
}
