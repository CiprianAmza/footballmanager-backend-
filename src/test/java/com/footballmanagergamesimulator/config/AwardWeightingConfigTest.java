package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AwardWeightingConfigTest {

    @Test
    void bindsLeagueTiersAndGoldenBootWeights() {
        AwardWeightingConfig config = new Binder(new MapConfigurationPropertySource(Map.of(
                "awards.weighting.league-strength.top-players-per-team", 9,
                "awards.weighting.league-strength.rank-multipliers[3]", 5.0,
                "awards.weighting.league-strength.rank-multipliers[5]", 2.0,
                "awards.weighting.league-strength.rank-multipliers[7]", 0.75,
                "awards.weighting.league-strength.default-multiplier", 0.5,
                "awards.weighting.golden-boot.goal-weight", 3.0,
                "awards.weighting.golden-boot.assist-weight", 1.25
        ))).bind("awards.weighting", AwardWeightingConfig.class)
                .orElseGet(AwardWeightingConfig::new);

        assertEquals(9, config.getLeagueStrength().getTopPlayersPerTeam());
        assertEquals(5.0, config.getLeagueStrength().multiplierForRank(2));
        assertEquals(2.0, config.getLeagueStrength().multiplierForRank(5));
        assertEquals(0.75, config.getLeagueStrength().multiplierForRank(7));
        assertEquals(0.5, config.getLeagueStrength().multiplierForRank(8));
        assertEquals(3.0, config.getGoldenBoot().getGoalWeight());
        assertEquals(1.25, config.getGoldenBoot().getAssistWeight());
    }
}
