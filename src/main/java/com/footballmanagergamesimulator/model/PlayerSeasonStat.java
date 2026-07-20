package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Analytics Faza 2 — accumulated synthetic per-player statistics earned across the
 * <b>real</b> matches a player actually appeared in, scoped to (player, competition,
 * season). One row per (playerId, competitionId, seasonNumber); each played match
 * increments {@link #appearances}/{@link #minutes} and adds a deterministic per-match
 * synthetic tally (derived from attributes + the team's tactic) to each accumulator.
 *
 * <p>These numbers are written <i>after</i> the scoreline is decided and never feed
 * back into match value, so the Poisson engine stays deterministic. Read-only tactic
 * tools ({@code TacticSimulationService}, {@code BestTacticService}) never reach the
 * recording hooks, so they produce no rows here.
 *
 * <p>{@code PlayerAnalyticsService} reads these accumulated sums (×90/minutes) when a
 * player has >= {@code analytics.minAppearances}, otherwise it falls back to the Faza 1
 * attribute synthesis.
 */
@Entity
@Data
@Table(name = "player_season_stat", indexes = {
        @Index(name = "idx_pss_comp_season", columnList = "competitionId,seasonNumber"),
        @Index(name = "idx_pss_player_season", columnList = "playerId,seasonNumber"),
        @Index(name = "idx_pss_team_season", columnList = "teamId,seasonNumber")
})
public class PlayerSeasonStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private long teamId;
    private long competitionId;
    private int seasonNumber;

    /** Real matches this player started/appeared in for this (competition, season). */
    private int appearances;
    /** Total minutes played (90 per start in the current engine). */
    private int minutes;

    // --- Synthetic accumulators (summed across played matches; turned into per-90 by the analytics service) ---
    @Column(name = "defensive_actions")
    private double defensiveActions;
    @Column(name = "pressures")
    private double pressures;
    @Column(name = "counterpressures")
    private double counterpressures;
    @Column(name = "tackles")
    private double tackles;
    @Column(name = "shots")
    private double shots;
    @Column(name = "passes_attempted")
    private double passesAttempted;
    @Column(name = "passes_completed")
    private double passesCompleted;
    /** Key passes / chances created while the player was on the pitch. */
    @Column(name = "chances_created")
    private double chancesCreated;
    /** Successful take-ons. Used together with chances created for the entertainment award. */
    @Column(name = "dribbles_completed")
    private double dribblesCompleted;
}
