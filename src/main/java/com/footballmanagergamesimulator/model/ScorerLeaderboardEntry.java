package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="scorerLeaderboardEntry")
public class ScorerLeaderboardEntry {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String name;
    private String position;

    private long teamId;
    @JsonProperty("isActive")
    private boolean isActive;

    private String teamName;

    private int goals;
    private int matches;

    private double currentRating;
    private int age;

    private double bestEverRating;
    private int seasonOfBestEverRating;

    private int leagueGoals;
    private int leagueMatches;

    private int cupGoals;
    private int cupMatches;

    private int secondLeagueGoals;
    private int secondLeagueMatches;

    private int currentSeasonGoals;
    private int currentSeasonGames;

    private int currentSeasonLeagueGoals;
    private int currentSeasonLeagueGames;

    private int currentSeasonCupGoals;
    private int currentSeasonCupGames;

    private int currentSeasonSecondLeagueGoals;
    private int currentSeasonSecondLeagueGames;
}
