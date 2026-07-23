package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainSnapshotFactoryTest {

    @Test
    void copiesEveryDomainFieldFromRealEntities() {
        Human player = AdapterTestFixture.human(42L, "AMC", 88.0, 65.0);
        PlayerSkills skills = AdapterTestFixture.skillsAll(14);
        FormationData slot = new FormationData();
        slot.setRole("Advanced Playmaker");
        slot.setDuty("Support");
        slot.setInstructions(List.of("Get Further Forward"));

        DomainPlayerSnapshot snapshot =
                DomainSnapshotFactory.fromLineupSlot(player, skills, slot, "AMC", 0.9, 80.0);

        assertThat(snapshot.playerId()).isEqualTo(42L);
        assertThat(snapshot.usedPosition()).isEqualTo("AMC");
        assertThat(snapshot.naturalPosition()).isEqualTo("AMC");
        assertThat(snapshot.roleDisplayName()).isEqualTo("Advanced Playmaker");
        assertThat(snapshot.dutyLabel()).isEqualTo("Support");
        assertThat(snapshot.fitness()).isEqualTo(88.0);
        assertThat(snapshot.morale()).isEqualTo(65.0);
        assertThat(snapshot.positionFamiliarity()).isEqualTo(0.9);
        assertThat(snapshot.roleSuitability()).isEqualTo(80.0);
        assertThat(snapshot.attributes()).hasSize(PlayerAttribute.values().length);
        assertThat(snapshot.attributes().get(PlayerAttribute.FINISHING)).isEqualTo(14);
    }

    @Test
    void snapshotIsIsolatedFromLaterEntityMutation() {
        Human player = AdapterTestFixture.human(1L, "ST", 100.0, 70.0);
        PlayerSkills skills = AdapterTestFixture.skillsAll(15);

        DomainPlayerSnapshot snapshot =
                DomainSnapshotFactory.fromDomain(player, skills, "ST", "Poacher", "Attack");

        // Mutate the source entities after the snapshot has been taken.
        player.setFitness(1.0);
        player.setMorale(1.0);
        skills.setFinishing(1);

        assertThat(snapshot.fitness()).isEqualTo(100.0);
        assertThat(snapshot.morale()).isEqualTo(70.0);
        assertThat(snapshot.attributes().get(PlayerAttribute.FINISHING)).isEqualTo(15);
    }

    @Test
    void attributeMapIsUnmodifiable() {
        Human player = AdapterTestFixture.human(1L, "MC", 100.0, 70.0);
        PlayerSkills skills = AdapterTestFixture.skillsAll(12);
        DomainPlayerSnapshot snapshot =
                DomainSnapshotFactory.fromDomain(player, skills, "MC", "Central Midfielder", "Support");

        assertThatThrownBy(() -> snapshot.attributes().put(PlayerAttribute.PASSING, 20))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convenienceOverloadLeavesFamiliarityAndSuitabilityForAdapterDefaults() {
        Human player = AdapterTestFixture.human(7L, "DC", 90.0, 70.0);
        PlayerSkills skills = AdapterTestFixture.skillsAll(13);

        DomainPlayerSnapshot snapshot =
                DomainSnapshotFactory.fromDomain(player, skills, "DC", "Central Defender", "Defend");

        assertThat(snapshot.positionFamiliarity()).isNull();
        assertThat(snapshot.roleSuitability()).isNull();
    }

    @Test
    void nullSlotYieldsNullRoleAndDuty() {
        Human player = AdapterTestFixture.human(9L, "DM", 90.0, 70.0);
        PlayerSkills skills = AdapterTestFixture.skillsAll(13);

        DomainPlayerSnapshot snapshot =
                DomainSnapshotFactory.fromLineupSlot(player, skills, null, "DM", null, null);

        assertThat(snapshot.roleDisplayName()).isNull();
        assertThat(snapshot.dutyLabel()).isNull();
    }

    @Test
    void requiresPlayerAndSkills() {
        PlayerSkills skills = AdapterTestFixture.skillsAll(10);
        assertThatThrownBy(() -> DomainSnapshotFactory.fromDomain(null, skills, "MC", "x", "Support"))
                .isInstanceOf(NullPointerException.class);
        Human player = AdapterTestFixture.human(1L, "MC", 100.0, 70.0);
        assertThatThrownBy(() -> DomainSnapshotFactory.fromDomain(player, null, "MC", "x", "Support"))
                .isInstanceOf(NullPointerException.class);
    }
}
