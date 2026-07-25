package com.footballmanagergamesimulator.compartment;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerPositionTest {
    @Test
    void exposesAllCanonicalCodes() {
        assertThat(Arrays.stream(PlayerPosition.values()).map(PlayerPosition::code))
                .containsExactly("GK", "DC", "DL", "DR", "WBL", "WBR", "DM", "MC",
                        "ML", "MR", "AMC", "AML", "AMR", "ST");
    }

    @Test
    void parserIsExplicitButNormalizesTrimAndCase() {
        assertThat(PlayerPosition.parse(" gk ")).contains(PlayerPosition.GK);
        assertThat(PlayerPosition.parse("amr")).contains(PlayerPosition.AMR);
        assertThat(PlayerPosition.parse("centre back")).isEmpty();
        assertThat(PlayerPosition.parse(null)).isEmpty();
        assertThat(PlayerPosition.parse(" ")).isEmpty();
        assertThatThrownBy(() -> PlayerPosition.require("DMC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown player position");
    }

    @Test
    void metadataHelpersAreTyped() {
        assertThat(PlayerPosition.GK.isGoalkeeper()).isTrue();
        assertThat(PlayerPosition.DC.isCentreBack()).isTrue();
        assertThat(PlayerPosition.DC.isCenterBack()).isTrue();
        assertThat(PlayerPosition.DM.isDefensiveMidfielder()).isTrue();
        assertThat(PlayerPosition.DL.isLeft()).isTrue();
        assertThat(PlayerPosition.DR.isRight()).isTrue();
        assertThat(PlayerPosition.MC.isCentral()).isTrue();
        assertThat(PlayerPosition.ML.isWideEligible()).isTrue();
        assertThat(PlayerPosition.AML.isHalfSpaceEligible()).isTrue();
    }
}
