package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class CalendarEntryView {

    private int roundNumber;
    private String competitionName;
    private long competitionId;
    private String competitionType; // "League", "Cup", "European"
    private String opponentTeamName;
    private long opponentTeamId;
    private String homeOrAway; // "H" or "A"
    private String score; // e.g. "2-1" or "-"
    private String resultOutcome; // "W", "D", "L" or null
    private String status; // "played" or "upcoming"
    private long teamId1;
    private long teamId2;
    private int seasonNumber;
}
