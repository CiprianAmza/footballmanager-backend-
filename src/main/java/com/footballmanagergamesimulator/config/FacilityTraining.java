package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.model.TeamFacilities;

/**
 * Single source of truth for the club training-facility development factor.
 *
 * <p>Replaces the {@code 0.5 + level/20} formula that used to be hardcoded in
 * several services. The factor is driven by {@link MatchEngineConfig.Training}
 * (so prod and tests read the same values) and selects the facility level by
 * player age: youngsters develop through the <b>youth</b> training facility,
 * players past {@code youthMaxAge} through the <b>senior</b> training facility —
 * both stored per team in {@link TeamFacilities} (1..20).
 *
 * <p>The factor multiplies positive attribute growth (and, when enabled, fitness
 * recovery); attribute decline is never accelerated by facilities.
 */
public final class FacilityTraining {

    private FacilityTraining() {}

    /**
     * Development factor for a player of {@code age} at a club with the given
     * facilities. Returns a neutral {@code 1.0} when facilities are unknown so a
     * missing row never zeroes out training.
     */
    public static double developmentFactor(MatchEngineConfig.Training cfg, TeamFacilities facilities, int age) {
        if (facilities == null) return 1.0;
        long level = age <= cfg.getYouthMaxAge()
                ? facilities.getYouthTrainingLevel()
                : facilities.getSeniorTrainingLevel();
        return cfg.getFacilityBase() + level / cfg.getFacilityDivisor();
    }
}
