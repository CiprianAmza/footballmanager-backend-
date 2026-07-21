package com.footballmanagergamesimulator.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagerTacticPolicyTest {

    @Test
    void eliteClubDefaultsAreExplicitAndCaseInsensitive() {
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("Shadows")).isTrue();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("Tik Tok")).isTrue();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("Inazuma Japan")).isTrue();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("FC San Marino")).isTrue();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("  shadows  ")).isTrue();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic("Sherlock FC")).isFalse();
        assertThat(ManagerTacticPolicy.defaultsToBestPossibleTactic(null)).isFalse();
    }
}
