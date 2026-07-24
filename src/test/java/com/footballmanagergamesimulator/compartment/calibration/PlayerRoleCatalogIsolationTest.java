package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.service.PlayerRoleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerRoleCatalogIsolationTest {
    @Test
    void shadowStrikerIsNotSelectableByLiveRoleService() {
        PlayerRoleService service = new PlayerRoleService();
        assertThat(service.getRoleNames("MC")).containsExactly(
                "Central Midfielder", "Deep-Lying Playmaker", "Ball-Winning Midfielder",
                "Box-to-Box Midfielder", "Advanced Playmaker", "Mezzala");
        assertThat(service.getRoleNames("MC")).doesNotContain("Shadow Striker");
    }
}
