package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionRoleKeyTest {
    @Test
    void acceptsOnlyValidPositionRolePairs() {
        assertThat(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER).role())
                .isEqualTo(PlayerRole.POACHER);
        assertThatThrownBy(() -> new PositionRoleKey(PlayerPosition.GK, PlayerRole.POACHER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void roleCodesAreStableEnumCodes() {
        assertThat(PositionRoleKey.ofCodes(" st ", "POACHER"))
                .isEqualTo(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER));
        assertThatThrownBy(() -> PositionRoleKey.ofCodes("ST", "Poacher"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown player role");
    }
}
