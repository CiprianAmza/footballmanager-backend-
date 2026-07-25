package com.footballmanagergamesimulator.compartment.effects;

/**
 * Immutable, versioned parameters for the canonical match-stat projection.
 * This profile is intentionally independent of MatchEngineConfig.Stats.
 */
public record CanonicalMatchStatsProfileV1(
        String version,
        int possessionBase,
        double possessionPowerScale,
        double possessionNoise,
        int passesBase,
        double passesNoise,
        int passAccuracyBase,
        double passAccuracyPowerScale,
        double passAccuracyNoise,
        double shotsBase,
        double shotsPowerScale,
        double shotsNoise,
        int maxShots,
        double blockedRate,
        double blockedNoise,
        double bigChanceRate,
        double xgPerGoal,
        double xgPerOtherShot,
        int cornersBase,
        double cornersPerShot,
        int foulsBase,
        double foulNoise,
        int tacklesBase,
        double tackleNoise,
        int interceptionsBase,
        double interceptionsNoise,
        int clearancesBase,
        double clearancesPerShot,
        int crossesBase,
        double crossesNoise,
        int duelsBase,
        double duelsNoise,
        int aerialDuelsBase,
        double aerialDuelsNoise) {

    public static CanonicalMatchStatsProfileV1 v1() {
        return new CanonicalMatchStatsProfileV1(
                "canonical-match-stats-v1",
                50, 18.0, 2.0,
                440, 28.0,
                82, 12.0, 2.5,
                10.5, 7.0, 1.25,
                35, 0.22, 0.10,
                0.12, 0.72, 0.055,
                5, 0.30,
                11, 1.2,
                18, 2.0,
                11, 1.5,
                18, 0.45,
                18, 2.0,
                55, 5.0,
                12, 2.0);
    }
}
