package com.footballmanagergamesimulator.compartment.effects;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMatchStatsSeedTest {
    @Test
    void sameDecisionAndSplitProduceTheSameSeed() {
        MatchScoringDecision decision = decision("CTIM:1", 7L, ScoreEngineKind.COMPARTMENT_V1,
                "a".repeat(64), "b".repeat(64));
        KnockoutPlanSplit split = KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null);

        assertThat(CanonicalMatchStatsSeed.derive(decision, split))
                .isEqualTo(CanonicalMatchStatsSeed.derive(decision, split));
    }

    @Test
    void everyDecisionIdentityAndSplitFieldContributesToTheSeed() {
        MatchScoringDecision base = decision("CTIM:1", 7L, ScoreEngineKind.COMPARTMENT_V1,
                "a".repeat(64), "b".repeat(64));
        KnockoutPlanSplit split = KnockoutPlanSplit.regularOnly(1, 0);
        long seed = CanonicalMatchStatsSeed.derive(base, split);

        assertThat(CanonicalMatchStatsSeed.derive(decision("CTIM:2", 7L, ScoreEngineKind.COMPARTMENT_V1,
                "a".repeat(64), "b".repeat(64)), split)).isNotEqualTo(seed);
        assertThat(CanonicalMatchStatsSeed.derive(decision("CTIM:1", 8L, ScoreEngineKind.COMPARTMENT_V1,
                "a".repeat(64), "b".repeat(64)), split)).isNotEqualTo(seed);
        assertThat(CanonicalMatchStatsSeed.derive(decision("CTIM:1", 7L, ScoreEngineKind.ADMIN_OVERRIDE,
                "a".repeat(64), "b".repeat(64)), split)).isNotEqualTo(seed);
        assertThat(CanonicalMatchStatsSeed.derive(decision("CTIM:1", 7L, ScoreEngineKind.COMPARTMENT_V1,
                "c".repeat(64), "b".repeat(64)), split)).isNotEqualTo(seed);
        assertThat(CanonicalMatchStatsSeed.derive(decision("CTIM:1", 7L, ScoreEngineKind.COMPARTMENT_V1,
                "a".repeat(64), "c".repeat(64)), split)).isNotEqualTo(seed);
        assertThat(CanonicalMatchStatsSeed.derive(base, KnockoutPlanSplit.regularOnly(2, 0)))
                .isNotEqualTo(seed);
    }

    private static MatchScoringDecision decision(String fixture, long seed, ScoreEngineKind engine,
                                                  String config, String input) {
        return new MatchScoringDecision(fixture, seed, engine, engine.algorithmVersion(), config, input,
                1, 0, 5_000, 4_000, null, null);
    }
}
