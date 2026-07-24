package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompartmentRolloutValidatorTest {
    @Test
    void compartmentOffIsValidAndBothDefaultsStayOff() {
        CompartmentEngineConfig compartment = new CompartmentEngineConfig();
        MatchEngineConfig match = new MatchEngineConfig();
        assertThatCode(() -> new CompartmentRolloutValidator(compartment, match).validateAtStartup())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(compartment.isEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(compartment.isShadowEnabled()).isFalse();
    }

    @Test
    void authoritativeCompartmentRequiresMatchPlan() {
        CompartmentEngineConfig compartment = new CompartmentEngineConfig();
        compartment.setEnabled(true);
        MatchEngineConfig match = new MatchEngineConfig();
        assertThatThrownBy(() -> new CompartmentRolloutValidator(compartment, match).validateAtStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("match.engine.match-plan.enabled");

        match.getMatchPlan().setEnabled(true);
        assertThatCode(() -> new CompartmentRolloutValidator(compartment, match).validateAtStartup())
                .doesNotThrowAnyException();
    }

    @Test
    void authoritativeAndShadowFlagsCannotBothBeOn() {
        CompartmentEngineConfig compartment = new CompartmentEngineConfig();
        compartment.setEnabled(true);
        compartment.setShadowEnabled(true);
        MatchEngineConfig match = new MatchEngineConfig();
        match.getMatchPlan().setEnabled(true);
        assertThatThrownBy(() -> new CompartmentRolloutValidator(compartment, match).validateAtStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }
}
