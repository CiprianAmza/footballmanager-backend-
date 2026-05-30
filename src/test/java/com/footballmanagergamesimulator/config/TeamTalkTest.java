package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for {@link MatchEngineConfig.TeamTalk#multiplier} — the deterministic,
 * manager-quality-driven team-talk multiplier applied to team power at scoring time.
 */
class TeamTalkTest {

    @Test
    void neutralReputation_givesNeutralMultiplier() {
        MatchEngineConfig.TeamTalk t = new MatchEngineConfig().getTeamTalk();
        assertThat(t.multiplier(t.getNeutralReputation())).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void highReputation_clampsToPlusMaxSwing_lowToMinusMaxSwing() {
        MatchEngineConfig.TeamTalk t = new MatchEngineConfig().getTeamTalk();
        double hi = t.getNeutralReputation() + 10 * t.getReputationSpan(); // well past the span
        double lo = 0.0;

        assertThat(t.multiplier(hi)).isCloseTo(1.0 + t.getMaxSwing(), within(1e-9));
        assertThat(t.multiplier(lo)).isLessThan(1.0).isGreaterThanOrEqualTo(1.0 - t.getMaxSwing());
    }

    @Test
    void scalesLinearlyBetweenNeutralAndSpan() {
        MatchEngineConfig.TeamTalk t = new MatchEngineConfig().getTeamTalk();
        double halfway = t.getNeutralReputation() + 0.5 * t.getReputationSpan();
        assertThat(t.multiplier(halfway)).isCloseTo(1.0 + 0.5 * t.getMaxSwing(), within(1e-9));
    }

    @Test
    void disabled_isAlwaysNeutral() {
        MatchEngineConfig.TeamTalk t = new MatchEngineConfig().getTeamTalk();
        t.setEnabled(false);
        assertThat(t.multiplier(0.0)).isEqualTo(1.0);
        assertThat(t.multiplier(99999.0)).isEqualTo(1.0);
    }
}
