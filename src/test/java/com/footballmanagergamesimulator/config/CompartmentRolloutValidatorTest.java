package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompartmentRolloutValidatorTest {
    @Test
    void disabledMatchPlanIsRejectedBecauseCanonicalScoringIsAlwaysAuthoritative() {
        MatchEngineConfig match = new MatchEngineConfig();
        assertThatThrownBy(() -> new CompartmentRolloutValidator(match).validateAtStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("match.engine.match-plan.enabled");
    }

    @Test
    void enabledMatchPlanIsValid() {
        MatchEngineConfig match = new MatchEngineConfig();
        match.getMatchPlan().setEnabled(true);
        assertThatCode(() -> new CompartmentRolloutValidator(match).validateAtStartup())
                .doesNotThrowAnyException();
    }
}
