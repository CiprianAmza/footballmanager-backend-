package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationTeamTest {
    @Test
    void stayForwardTargetsExactlyTheRepresentativePoacher() {
        var team = CalibrationScenarioFixtures.selectedWeights().baselineTeam().withStayForward();
        var active = team.players().stream()
                .filter(player -> player.instruction() == ForwardInstruction.STAY_FORWARD)
                .toList();

        assertThat(active).hasSize(1);
        assertThat(active.get(0).position()).isEqualTo(PlayerPosition.ST);
        assertThat(active.get(0).role()).isEqualTo(PlayerRole.POACHER);
        assertThat(team.players()).filteredOn(player -> player.position() == PlayerPosition.DC)
                .noneMatch(player -> player.instruction() == ForwardInstruction.STAY_FORWARD);
    }
}
