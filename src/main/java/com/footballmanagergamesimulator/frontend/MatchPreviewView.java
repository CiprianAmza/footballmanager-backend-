package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

@Data
public class MatchPreviewView {

    private long homeTeamId;
    private String homeTeamName;
    private long awayTeamId;
    private String awayTeamName;

    private String competitionName;
    private long competitionId;
    private int round;

    // Form (last 5 results as "WWDLW" string)
    private String homeForm;
    private String awayForm;

    // League positions (0 if not in a league)
    private int homeLeaguePosition;
    private int awayLeaguePosition;

    // Head-to-head stats
    private int h2hHomeWins;
    private int h2hAwayWins;
    private int h2hDraws;

    // Prediction based on power ratio
    private String prediction;
    private int homePowerRating;
    private int awayPowerRating;
}
