package com.footballmanagergamesimulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Trust between chairman, general director and manager (design DESIGN_START_CARIERA §5.0.1, decided 2026-11-13).
 * All on 0-100; the defaults are the design's values and can be tuned in {@code game.board-trust}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "game.board-trust")
public class BoardTrustConfig {
    private int win = 2;
    private int loss = -2;
    private int trophy = 15;
    private int promiseKept = 10;
    private int promiseMissed = -10;
    /** Below this, the chairman sacks whoever he trusts less: the manager or the director. */
    private int sackThreshold = 20;
    /** The chairman's starting trust in a manager without the licence his league needs (§3). */
    private int unlicensedStart = 35;
    /** Chairman's trust in a director: 50 plus this per season in office, at most {@link #directorTrustMax}. */
    private int directorTrustPerSeason = 6;
    private int directorTrustMax = 80;
    /** Director's starting trust in a new manager, by personality. */
    private int directorSupportiveStart = 65;
    private int directorNeutralStart = 50;
    private int directorOwnerLoyalStart = 50;
    private int directorAmbitiousStart = 40;
}
