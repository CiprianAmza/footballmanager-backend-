package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** One team's pressing and defensive ledger for a played match. */
@Entity
@Data
@Table(name = "defensive_pressure",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_stats_id", "team_id"}),
        indexes = @Index(name = "idx_def_pressure_team_season", columnList = "team_id,season_number"))
public class DefensivePressure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private long id;
    @Column(name = "match_stats_id", nullable = false) private long matchStatsId;
    @Column(name = "competition_id", nullable = false) private long competitionId;
    @Column(name = "season_number", nullable = false) private int seasonNumber;
    @Column(name = "round_number", nullable = false) private int roundNumber;
    @Column(name = "team_id", nullable = false) private long teamId;
    @Column(name = "opponent_team_id", nullable = false) private long opponentTeamId;

    private double ppdaProxy;
    private int pressures;
    private int successfulPressures;
    private int counterpressures;
    private int regainsWithinFiveSeconds;
    private int regainsWithinEightSeconds;
    private double ballRecoveryTimeSeconds;
    private int highTurnovers;
    private int highTurnoversToShot;
    private int highTurnoversToGoal;
    private int forcedTurnovers;
    private int recoveriesLeft;
    private int recoveriesCentre;
    private int recoveriesRight;
    private int defensiveActionsOppositionHalf;
    private int allowedBoxEntries;
    private int transitionShotsAllowed;
    private int errorsLeadingToShot;
    private int errorsLeadingToGoal;
    private int duels;
    private int duelsWon;
    private int clearances;
    private int blocks;
    private int interceptions;
    private double defensiveLineHeightMeters;
    private String dataQuality;
}
