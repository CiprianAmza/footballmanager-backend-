package com.footballmanagergamesimulator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/** One team's internally consistent possession/progression ledger for a played match. */
@Entity
@Data
@Table(name = "possession_progression",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_stats_id", "team_id"}),
        indexes = @Index(name = "idx_progression_team_season", columnList = "team_id,season_number"))
public class PossessionProgression {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "match_stats_id", nullable = false)
    private long matchStatsId;
    @Column(name = "competition_id", nullable = false)
    private long competitionId;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "round_number", nullable = false)
    private int roundNumber;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(name = "opponent_team_id", nullable = false)
    private long opponentTeamId;

    private int possessions;
    private int completedPasses;
    private int progressivePasses;
    private int progressiveCarries;
    private int finalThirdEntries;
    private int penaltyAreaEntries;
    private int lineBreakingPasses;
    private int passesIntoBox;
    private int receptionsBetweenLines;
    private int switchesOfPlay;
    private double fieldTiltPercentage;
    private double passesPerPossession;
    private double averagePossessionDurationSeconds;
    private double directSpeedMetersPerSecond;
    private int tenPassSequences;
    private int buildUpAttacks;
    private int directAttacks;
    private String dataQuality;
}
