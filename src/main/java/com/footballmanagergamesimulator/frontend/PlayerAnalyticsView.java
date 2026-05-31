package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.util.List;

/**
 * Synthetic StatsBomb-style analytics for one (player, competition, season).
 *
 * <p>Produced by {@code PlayerAnalyticsService} — per-90 "expected" metrics are
 * synthesized from {@code PlayerSkills} attributes and ranked into percentiles
 * versus same-position-group peers in the same competition+season. The heatmap is
 * a per-position-group density template modulated by the player's attributes. No
 * real event data exists (the match engine is Poisson) so everything is derived.
 */
@Data
public class PlayerAnalyticsView {

    private long playerId;
    private String playerName;
    private String position;
    private String positionGroup;
    private long competitionId;
    private int seasonNumber;

    /** Human.rating (1-300) — drives the colored overall badge on the FE. */
    private double overall;

    /** Appearances behind these numbers in this (competition, season). */
    private int sampleAppearances;
    /** Number of same-position-group peers (>= minAppearances) the percentiles are ranked against. */
    private int peerCount;

    /**
     * Faza 2 flag: {@code true} when the metrics are computed from REAL accumulated
     * per-match stats ({@code PlayerSeasonStat}); {@code false} when they fall back to
     * the Faza 1 attribute projection. Lets the FE label numbers "real" vs "projected".
     */
    private boolean accumulated;

    private List<MetricEntry> metrics;

    /** Row-major grid of densities (0..1); rows = top→bottom of pitch, cols = own→opp goal. */
    private double[][] heatmap;

    @Data
    public static class MetricEntry {
        private String metric;
        private double valuePer90;
        /** 0..100 percentile vs peers (50 == median). */
        private double percentile;

        public MetricEntry() {}

        public MetricEntry(String metric, double valuePer90, double percentile) {
            this.metric = metric;
            this.valuePer90 = valuePer90;
            this.percentile = percentile;
        }
    }
}
