package com.footballmanagergamesimulator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerContextModelValidationTest {
    @Test
    void positionFamiliarityAcceptsBoundsAndRejectsOutsideRange() {
        PlayerPositionFamiliarity row = new PlayerPositionFamiliarity();
        row.setPositionCode(" gk ");
        row.setFamiliarity(1);
        assertThatCode(row::validate).doesNotThrowAnyException();
        row.setFamiliarity(20);
        assertThatCode(row::validate).doesNotThrowAnyException();
        row.setFamiliarity(0);
        assertThatThrownBy(row::validate).isInstanceOf(IllegalArgumentException.class);
        row.setFamiliarity(21);
        assertThatThrownBy(row::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleFamiliarityValidatesPositionRoleCodesAndBounds() {
        PlayerRoleFamiliarity row = new PlayerRoleFamiliarity();
        row.setPositionCode("ST");
        row.setRoleCode("POACHER");
        row.setFamiliarity(20);
        assertThatCode(row::validate).doesNotThrowAnyException();
        row.setRoleCode("NO_SUCH_ROLE");
        assertThatThrownBy(row::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleFamiliarityRejectsUnavailableGoalkeeperRoleAndNormalizesCodes() {
        PlayerRoleFamiliarity row = new PlayerRoleFamiliarity();
        row.setPositionCode(" gk ");
        row.setRoleCode("POACHER");
        row.setFamiliarity(10);
        assertThatThrownBy(row::validate).isInstanceOf(IllegalArgumentException.class);

        row.setPositionCode(" st ");
        row.setRoleCode("poacher");
        row.validate();
        org.assertj.core.api.Assertions.assertThat(row.getPositionCode()).isEqualTo("ST");
        org.assertj.core.api.Assertions.assertThat(row.getRoleCode()).isEqualTo("POACHER");
    }

    @Test
    void footProfileAcceptsBoundsAndRejectsInvalidRatings() {
        PlayerFootProfile profile = new PlayerFootProfile();
        profile.setLeftFootRating(1);
        profile.setRightFootRating(20);
        assertThatCode(profile::validate).doesNotThrowAnyException();
        profile.setLeftFootRating(0);
        assertThatThrownBy(profile::validate).isInstanceOf(IllegalArgumentException.class);
        profile.setLeftFootRating(1);
        profile.setRightFootRating(21);
        assertThatThrownBy(profile::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
