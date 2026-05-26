package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "match_stats", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"competitionId", "seasonNumber", "roundNumber", "team1Id", "team2Id"})
})
public class MatchStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long competitionId;
    private int seasonNumber;
    private int roundNumber;
    private long team1Id; // home
    private long team2Id; // away

    // Score
    private int homeGoals;
    private int awayGoals;

    // Possession
    private int homePossession; // 0-100
    private int awayPossession;

    // Shots
    private int homeShots;
    private int awayShots;
    private int homeShotsOnTarget;
    private int awayShotsOnTarget;
    private int homeShotsBlocked;
    private int awayShotsBlocked;

    // Set pieces
    private int homeCorners;
    private int awayCorners;
    private int homeFreeKicks;
    private int awayFreeKicks;

    // Discipline
    private int homeFouls;
    private int awayFouls;
    private int homeYellowCards;
    private int awayYellowCards;
    private int homeRedCards;
    private int awayRedCards;
    private int homeOffsides;
    private int awayOffsides;

    // Passing
    private int homePasses;
    private int awayPasses;
    private int homePassAccuracy; // 0-100
    private int awayPassAccuracy;

    // Defensive
    private int homeTackles;
    private int awayTackles;
    private int homeInterceptions;
    private int awayInterceptions;
    private int homeClearances;
    private int awayClearances;

    // Goalkeeping
    private int homeSaves;
    private int awaySaves;

    // Advanced
    private int homeBigChances;
    private int awayBigChances;
    private int homeBigChancesMissed;
    private int awayBigChancesMissed;

    // xG (stored as int * 100 for precision, e.g. 1.52 xG = 152)
    private int homeXg;
    private int awayXg;

    // Crosses
    private int homeCrosses;
    private int awayCrosses;
    private int homeCrossesAccurate;
    private int awayCrossesAccurate;

    // Duels
    private int homeDuelsWon;
    private int awayDuelsWon;
    private int homeAerialDuelsWon;
    private int awayAerialDuelsWon;

    // Man of the Match
    private long homeManOfTheMatchId;
    private String homeManOfTheMatchName;
    private double homeManOfTheMatchRating;
    private long awayManOfTheMatchId;
    private String awayManOfTheMatchName;
    private double awayManOfTheMatchRating;
}
