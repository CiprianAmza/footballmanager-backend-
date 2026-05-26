package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Admin-set predetermined score for a specific upcoming match.
 * When the simulator reaches a match with a matching (competition, season, round, team1, team2)
 * tuple and {@code consumed = false}, it skips score calculation and uses the values here,
 * then marks the row as consumed so it isn't reused.
 */
@Entity
@Data
@Table(name = "predetermined_score",
       uniqueConstraints = @UniqueConstraint(columnNames = {
               "competition_id", "season_number", "round_number", "team1_id", "team2_id"
       }))
public class PredeterminedScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "competition_id")
    private long competitionId;

    @Column(name = "season_number")
    private int seasonNumber;

    @Column(name = "round_number")
    private int roundNumber;

    @Column(name = "team1_id")
    private long team1Id;

    @Column(name = "team2_id")
    private long team2Id;

    @Column(name = "team1_score")
    private int team1Score;

    @Column(name = "team2_score")
    private int team2Score;

    /** True once the simulator has used this score; we keep the row for history. */
    private boolean consumed = false;
}
