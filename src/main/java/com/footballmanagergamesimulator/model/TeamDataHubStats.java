package com.footballmanagergamesimulator.model;

import lombok.Data;

import java.util.List;

@Data
public class TeamDataHubStats {

    // Team info
    private long teamId;
    private String teamName;
    private int seasonNumber;

    // Aggregated match stats
    private int totalMatches;
    private int wins;
    private int draws;
    private int losses;

    // Goals
    private int goalsScored;
    private int goalsConceded;
    private double goalsPerGame;
    private double concededPerGame;

    // Player performance
    private double avgTeamRating;
    private int totalAssists;
    private double assistsPerGame;

    // Clean sheets
    private int cleanSheets;
    private double cleanSheetPercentage;

    // Win percentage
    private double winPercentage;

    // Top performers
    private String topScorer;
    private int topScorerGoals;
    private String topAssister;
    private int topAssisterAssists;
    private String highestRatedPlayer;
    private double highestRating;

    // Recent form (last 5 matches) - "W", "D", "L"
    private List<String> recentForm;

    // League averages (computed from all teams in same season)
    private double leagueAvgGoalsPerGame;
    private double leagueAvgConcededPerGame;
    private double leagueAvgRating;
    private double leagueAvgAssistsPerGame;
    private double leagueAvgCleanSheetPct;
    private double leagueAvgWinPct;
}
