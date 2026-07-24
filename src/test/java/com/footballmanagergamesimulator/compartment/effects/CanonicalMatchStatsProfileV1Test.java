package com.footballmanagergamesimulator.compartment.effects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMatchStatsProfileV1Test {
    @Test
    void v1IsFixedAndVersioned() {
        CanonicalMatchStatsProfileV1 first = CanonicalMatchStatsProfileV1.v1();
        CanonicalMatchStatsProfileV1 second = CanonicalMatchStatsProfileV1.v1();

        assertThat(first).isEqualTo(second);
        assertThat(first.version()).isEqualTo("canonical-match-stats-v1");
        assertThat(first.maxShots()).isPositive();
        assertThat(first.possessionBase()).isEqualTo(50);
    }
}
