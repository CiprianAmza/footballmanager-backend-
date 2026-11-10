package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** Immutable pre-season context; never recomputed from a later squad. */
@Entity
@Data
@Table(name = "competition_season_expectation",
        uniqueConstraints = @UniqueConstraint(name = "uk_cse_season_comp_team",
                columnNames = {"season_number", "competition_id", "team_id"}),
        indexes = {
                @Index(name = "idx_cse_season_comp", columnList = "season_number,competition_id"),
                @Index(name = "idx_cse_team_season", columnList = "team_id,season_number")
        })
public class CompetitionSeasonExpectation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "competition_id", nullable = false)
    private long competitionId;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    private int predictedPosition;
    private int fieldSize;
    private double topElevenRating;
    private int teamReputation;
    private double competitionPrestige;
    private Instant capturedAt;
}
