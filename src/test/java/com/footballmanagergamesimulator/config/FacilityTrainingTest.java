package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.model.TeamFacilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the single-source facility development factor. Pure function,
 * no Spring context — proves the age-based youth/senior selection and the
 * {@code base + level/divisor} formula that prod and tests both rely on.
 */
@DisplayName("FacilityTraining.developmentFactor: age-based youth/senior level + formula")
class FacilityTrainingTest {

    private final MatchEngineConfig.Training cfg = new MatchEngineConfig.Training(); // defaults: 0.5, 20, age22

    private TeamFacilities facilities(long youth, long senior) {
        TeamFacilities f = new TeamFacilities();
        f.setYouthTrainingLevel(youth);
        f.setSeniorTrainingLevel(senior);
        return f;
    }

    @Test
    @DisplayName("Young players (≤ youthMaxAge) use the youth training level")
    void youngUsesYouthLevel() {
        TeamFacilities f = facilities(20, 1); // youth maxed, senior minimal
        // age 18 ≤ 22 → youth level 20 → 0.5 + 20/20 = 1.5
        assertThat(FacilityTraining.developmentFactor(cfg, f, 18)).isCloseTo(1.5, within(1e-9));
        // boundary: exactly youthMaxAge (22) still youth
        assertThat(FacilityTraining.developmentFactor(cfg, f, 22)).isCloseTo(1.5, within(1e-9));
    }

    @Test
    @DisplayName("Players past youthMaxAge use the senior training level")
    void seniorUsesSeniorLevel() {
        TeamFacilities f = facilities(20, 1); // youth maxed, senior minimal
        // age 23 > 22 → senior level 1 → 0.5 + 1/20 = 0.55
        assertThat(FacilityTraining.developmentFactor(cfg, f, 23)).isCloseTo(0.55, within(1e-9));
        assertThat(FacilityTraining.developmentFactor(cfg, f, 30)).isCloseTo(0.55, within(1e-9));
    }

    @Test
    @DisplayName("Factor scales monotonically with the facility level")
    void higherLevelGivesHigherFactor() {
        double low = FacilityTraining.developmentFactor(cfg, facilities(5, 5), 30);
        double high = FacilityTraining.developmentFactor(cfg, facilities(5, 18), 30);
        assertThat(high).as("a better senior facility must yield a higher factor").isGreaterThan(low);
        assertThat(low).isCloseTo(0.5 + 5 / 20.0, within(1e-9));
        assertThat(high).isCloseTo(0.5 + 18 / 20.0, within(1e-9));
    }

    @Test
    @DisplayName("Null facilities yield a neutral factor of 1.0 (never zeroes out training)")
    void nullFacilitiesAreNeutral() {
        assertThat(FacilityTraining.developmentFactor(cfg, null, 20)).isEqualTo(1.0);
        assertThat(FacilityTraining.developmentFactor(cfg, null, 30)).isEqualTo(1.0);
    }
}
