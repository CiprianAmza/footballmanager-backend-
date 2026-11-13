package com.footballmanagergamesimulator.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How well a player is trained on one type, as {@code q} in 0..1: the chance that a rise of
 * the monthly budget actually happens. Built from three factors, each 10 = ×1:
 * <ul>
 *   <li>training facilities (1&ndash;20): ±{@code facilityStep} per level (20 → ×2, 1 → ×0.1);</li>
 *   <li>the player's professionalism (1&ndash;20): ±{@code professionalismStep} per point (20 → ×1.5);</li>
 *   <li>the coach delegated to the type (rating 1&ndash;20, average of his two attributes for it):
 *       ±{@code coachStep} per point (20 → ×2).</li>
 * </ul>
 * Each factor is divided by its maximum, then combined as a {@code PRODUCT} (everything must be
 * at 20 for q = 1; all at 10 gives 1/6) or an {@code AVERAGE} (all at 10 gives about 0.56).
 *
 * <p>Below the q of an ordinary club (all three at 10), decline is stronger: its chances are
 * multiplied linearly up to {@code maxDeclineMultiplier} at q = 0.
 */
@Component
@ConfigurationProperties(prefix = "simulation.training.quality")
@Getter
@Setter
public class TrainingQuality {

    public enum Combination { PRODUCT, AVERAGE }

    static final int NORMAL_LEVEL = 10;
    static final int MAX_LEVEL = 20;

    private Combination combination = Combination.PRODUCT;
    private double facilityStep = 0.1;
    private double professionalismStep = 0.05;
    private double coachStep = 0.1;
    private double maxDeclineMultiplier = 3.0;

    /** q for these levels (each 1&ndash;20; a missing coach counts as level 1). */
    public double q(double facilityLevel, double professionalism, double coachRating) {
        double facility = normalized(facilityLevel, facilityStep);
        double player = normalized(professionalism, professionalismStep);
        double coach = normalized(coachRating, coachStep);
        double q = combination == Combination.PRODUCT
                ? facility * player * coach
                : (facility + player + coach) / 3.0;
        return Math.max(0.0, Math.min(1.0, q));
    }

    /** The q of an ordinary club: all three at 10. Decline grows below it. */
    public double ordinaryQ() {
        return q(NORMAL_LEVEL, NORMAL_LEVEL, NORMAL_LEVEL);
    }

    /** How much stronger decline is at this q: 1 at or above an ordinary club, up to the maximum at q = 0. */
    public double declineMultiplier(double q) {
        double ordinary = ordinaryQ();
        if (q >= ordinary || ordinary <= 0) return 1.0;
        return 1.0 + (maxDeclineMultiplier - 1.0) * (ordinary - q) / ordinary;
    }

    /** The factor of a level (10 = ×1, ±step per level) divided by the factor at 20. */
    private static double normalized(double level, double step) {
        double clamped = Math.max(1, Math.min(MAX_LEVEL, level));
        double factor = 1.0 + step * (clamped - NORMAL_LEVEL);
        double max = 1.0 + step * (MAX_LEVEL - NORMAL_LEVEL);
        return Math.max(0.0, factor / max);
    }
}
