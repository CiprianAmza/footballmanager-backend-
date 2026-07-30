package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import com.footballmanagergamesimulator.service.PlayerRoleService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalRuntimeInputFactoryTest {
    private final PlayerCapabilityService capabilities = mock(PlayerCapabilityService.class);
    private final PlayerRoleService roles = mock(PlayerRoleService.class);
    private final CanonicalRuntimeInputFactory factory =
            new CanonicalRuntimeInputFactory(capabilities, roles);

    @Test
    void validXiUsesOneBatchCapabilityLoadAndDeterministicOrder() {
        List<RuntimeLineupSlot> slots = validSlots();
        Map<Long, PlayerCapabilitySnapshot> snapshots = snapshots(slots);
        when(capabilities.loadAll(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L)))
                .thenReturn(snapshots);
        List<RuntimeLineupSlot> shuffled = new ArrayList<>(slots);
        java.util.Collections.reverse(shuffled);

        CanonicalRuntimeTeamInput result = factory.build(tactic("Very Attacking"), shuffled);

        verify(capabilities).loadAll(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
        assertThat(result.lineup()).extracting(player -> player.playerId())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        assertThat(result.mentality()).isEqualTo(Mentality.VERY_ATTACKING);
        assertThat(result.tacticalContexts().keySet())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
    }

    @Test
    void canonicalMappingCoversRolesDutySuitabilityAndTacticalInstructions() {
        List<RuntimeLineupSlot> slots = validSlots();
        FormationData striker = formation(11L, "Poacher", "Attack", List.of("Stay Forward"));
        slots.set(10, new RuntimeLineupSlot(slots.get(10).player(), slots.get(10).skills(), striker,
                PlayerPosition.ST, 1));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        when(roles.computeRoleSuitability(any(PlayerSkills.class), eq("ST"), eq("Poacher"))).thenReturn(77.0);
        when(roles.isDutyAllowed(eq("ST"), eq("Poacher"), eq("ATTACK"))).thenReturn(true);
        slots.get(10).player().setStayForward(true);

        CanonicalRuntimeTeamInput result = factory.build(tactic("Balanced"), slots);
        var player = result.lineup().stream().filter(value -> value.playerId() == 11L).findFirst().orElseThrow();

        assertThat(player.role()).isEqualTo(PlayerRole.POACHER);
        assertThat(player.duty()).isEqualTo(Duty.ATTACK);
        assertThat(player.roleSuitability()).isEqualTo(77.0);
        assertThat(player.traits()).containsExactly(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        assertThat(player.forwardInstruction()).isEqualTo(ForwardInstruction.STAY_FORWARD);
        assertThat(result.tacticalContexts().get(11L).playerInstructions())
                .containsExactly("Stay Forward");
        verify(roles).computeRoleSuitability(any(PlayerSkills.class), eq("ST"), eq("Poacher"));
    }

    @Test
    void absentRoleIsNeutralAndDutyIsSupport() {
        List<RuntimeLineupSlot> slots = validSlots();
        slots.set(10, new RuntimeLineupSlot(slots.get(10).player(), slots.get(10).skills(), null,
                PlayerPosition.ST, 1));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));

        var player = factory.build(tactic("Balanced"), slots).lineup().stream()
                .filter(value -> value.playerId() == 11L).findFirst().orElseThrow();

        assertThat(player.role()).isNull();
        assertThat(player.duty()).isEqualTo(Duty.SUPPORT);
        assertThat(player.roleSuitability()).isEqualTo(50.0);
        verifyNoInteractions(roles);
    }

    @Test
    void dutyIsCaseInsensitiveButUnknownNonBlankDutyIsRejected() {
        List<RuntimeLineupSlot> slots = validSlots();
        slots.set(10, replaceFormation(slots.get(10), formation(11L, null, "dEfEnD", List.of())));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        assertThat(factory.build(tactic("Balanced"), slots).lineup().get(10).duty()).isEqualTo(Duty.DEFEND);

        List<RuntimeLineupSlot> invalid = validSlots();
        invalid.set(10, replaceFormation(invalid.get(10), formation(11L, null, "Libero", List.of())));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown duty");
    }

    @Test
    void mentalityMappingAndTacticalDefaultsAreCanonical() {
        Map<String, Mentality> expectedMentalities = Map.of(
                "Very Attacking", Mentality.VERY_ATTACKING,
                "Attacking", Mentality.ATTACKING,
                "Balanced", Mentality.BALANCED,
                "Defensive", Mentality.DEFENSIVE,
                "Very Defensive", Mentality.VERY_DEFENSIVE);
        for (Map.Entry<String, Mentality> entry : expectedMentalities.entrySet()) {
            List<RuntimeLineupSlot> slots = validSlots();
            when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
            assertThat(factory.build(tactic(entry.getKey()), slots).mentality()).isEqualTo(entry.getValue());
        }
        List<RuntimeLineupSlot> slots = validSlots();
        PersonalizedTactic tactic = tactic("  ");
        tactic.setTempo(" ");
        tactic.setPassingType(null);
        tactic.setDefensiveLine("");
        tactic.setPressing(null);
        tactic.setWidth(" ");
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        TacticalContextInput context = factory.build(tactic, slots).tacticalContexts().get(1L);
        assertThat(context).isEqualTo(new TacticalContextInput(
                "Balanced", "Standard", "Normal", "Standard", "Normal", "Balanced", List.of()));
    }

    @Test
    void invalidMentalityRoleAndIncompatibleRoleAreRejected() {
        List<RuntimeLineupSlot> slots = validSlots();
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        PersonalizedTactic invalidMentality = tactic("Unrealistic");
        assertThatThrownBy(() -> factory.build(invalidMentality, slots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown mentality");

        List<RuntimeLineupSlot> unknownRole = validSlots();
        unknownRole.set(10, replaceFormation(unknownRole.get(10), formation(11L, "Made Up", "Support", List.of())));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), unknownRole))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown role");

        List<RuntimeLineupSlot> incompatible = validSlots();
        incompatible.set(10, replaceFormation(incompatible.get(10), formation(11L, "Goalkeeper", "Support", List.of())));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void roleAndDutyValidationUsesCanonicalDefinitions() {
        assertInvalidRoleDuty(PlayerPosition.ST, "Poacher", "Defend");
        assertInvalidRoleDuty(PlayerPosition.GK, "Goalkeeper", "Attack");
        assertInvalidRoleDuty(PlayerPosition.DC, "No-Nonsense Defender", "Attack");

        assertValidRoleDuty(PlayerPosition.ST, "Poacher", "Attack");
        assertValidRoleDuty(PlayerPosition.GK, "Goalkeeper", "Defend");
        assertValidRoleDuty(PlayerPosition.MC, "Central Midfielder", "Support");
    }

    @Test
    void shooterIsExplicitAndUniquePerStartingEleven() {
        List<RuntimeLineupSlot> slots = validSlots();
        FormationData shooter = formation(10L, null, "Support", List.of());
        shooter.setSpecialRole("SHOOTER");
        slots.set(9, replaceFormation(slots.get(9), shooter));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));

        CanonicalRuntimeTeamInput input = factory.build(tactic("Balanced"), slots);
        assertThat(input.lineup().stream().filter(player -> player.traits().contains(PlayerTrait.SHOOTER)))
                .extracting(player -> player.playerId()).containsExactly(10L);

        FormationData secondShooter = formation(11L, null, "Support", List.of());
        secondShooter.setSpecialRole("SHOOTER");
        slots.set(10, replaceFormation(slots.get(10), secondShooter));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), slots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one SHOOTER");
    }

    @Test
    void tacticalAxesAreCanonicalizedAndUnknownValuesRejected() {
        PersonalizedTactic canonical = tactic("  vErY aTtAcKiNg ");
        canonical.setTempo(" hIgHeR ");
        canonical.setPassingType(" sHoRt ");
        canonical.setDefensiveLine(" hIgH ");
        canonical.setPressing(" hIgH ");
        canonical.setWidth(" wIdE ");
        List<RuntimeLineupSlot> slots = validSlots();
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        TacticalContextInput context = factory.build(canonical, slots).tacticalContexts().get(1L);
        assertThat(context.mentality()).isEqualTo("Very Attacking");
        assertThat(context.tempo()).isEqualTo("Higher");
        assertThat(context.passingType()).isEqualTo("Short");
        assertThat(context.defensiveLine()).isEqualTo("High");
        assertThat(context.pressing()).isEqualTo("Very Aggressive");
        assertThat(context.width()).isEqualTo("Wide");

        Map<String, java.util.function.Consumer<PersonalizedTactic>> invalid = Map.of(
                "mentality", value -> value.setMentality("invalid"),
                "tempo", value -> value.setTempo("invalid"),
                "passingType", value -> value.setPassingType("invalid"),
                "defensiveLine", value -> value.setDefensiveLine("invalid"),
                "pressing", value -> value.setPressing("invalid"),
                "width", value -> value.setWidth("invalid"));
        for (Map.Entry<String, java.util.function.Consumer<PersonalizedTactic>> entry : invalid.entrySet()) {
            PersonalizedTactic bad = tactic("Balanced");
            entry.getValue().accept(bad);
            assertThatThrownBy(() -> factory.build(bad, validSlots()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(entry.getKey())
                    .hasMessageContaining("invalid");
        }
    }

    private void assertInvalidRoleDuty(PlayerPosition position, String role, String duty) {
        List<RuntimeLineupSlot> slots = validSlots();
        int index = slots.indexOf(slots.stream().filter(slot -> slot.usedPosition() == position).findFirst().orElseThrow());
        RuntimeLineupSlot original = slots.get(index);
        slots.set(index, replaceFormation(original, formation(original.player().getId(), role, duty, List.of())));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        when(roles.isDutyAllowed(eq(position.code()), eq(role), eq(duty.toUpperCase()))).thenReturn(false);
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), slots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(role).hasMessageContaining(duty.toUpperCase()).hasMessageContaining(position.code());
    }

    private void assertValidRoleDuty(PlayerPosition position, String role, String duty) {
        List<RuntimeLineupSlot> slots = validSlots();
        int index = slots.indexOf(slots.stream().filter(slot -> slot.usedPosition() == position).findFirst().orElseThrow());
        RuntimeLineupSlot original = slots.get(index);
        slots.set(index, replaceFormation(original, formation(original.player().getId(), role, duty, List.of())));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        when(roles.isDutyAllowed(eq(position.code()), eq(role), eq(duty.toUpperCase()))).thenReturn(true);
        when(roles.computeRoleSuitability(any(PlayerSkills.class), eq(position.code()), eq(role))).thenReturn(60.0);
        assertThat(factory.build(tactic("Balanced"), slots).lineup()).isNotEmpty();
    }

    @Test
    void instructionConflictAndPersistentTraitAreIndependent() {
        List<RuntimeLineupSlot> conflict = validSlots();
        conflict.set(10, replaceFormation(conflict.get(10), formation(11L, null, "Support",
                List.of("Stay Forward", "Track Back"))));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(conflict));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot both");

        List<RuntimeLineupSlot> instructionOnly = validSlots();
        instructionOnly.set(10, replaceFormation(instructionOnly.get(10), formation(11L, null, "Support",
                List.of("Stay Forward"))));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(instructionOnly));
        var player = factory.build(tactic("Balanced"), instructionOnly).lineup().get(10);
        assertThat(player.traits()).isEmpty();
        assertThat(player.forwardInstruction()).isEqualTo(ForwardInstruction.STAY_FORWARD);
    }

    @Test
    void tacticalShadowUsesRefusesDefensiveWorkTraitAndIsNotUnique() {
        List<RuntimeLineupSlot> slots = validSlots();
        FormationData leftShadow = formation(7L, null, "Support", List.of());
        leftShadow.setShadow(true);
        leftShadow.setSpecialRole("SHOOTER");
        FormationData strikerShadow = formation(11L, null, "Attack", List.of());
        strikerShadow.setShadow(true);
        FormationData centralMidfielderShadow = formation(6L, null, "Attack", List.of());
        centralMidfielderShadow.setShadow(true);
        slots.set(5, replaceFormation(slots.get(5), centralMidfielderShadow));
        slots.set(6, replaceFormation(slots.get(6), leftShadow));
        slots.set(10, replaceFormation(slots.get(10), strikerShadow));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));

        var lineup = factory.build(tactic("Balanced"), slots).lineup();

        assertThat(lineup.stream()
                .filter(player -> player.traits().contains(PlayerTrait.REFUSES_DEFENSIVE_WORK)))
                .extracting(player -> player.playerId())
                .containsExactlyInAnyOrder(6L, 7L, 11L);
        assertThat(lineup.stream()
                .filter(player -> player.traits().contains(PlayerTrait.SHOOTER)))
                .singleElement()
                .satisfies(player -> assertThat(player.traits())
                        .contains(PlayerTrait.SHOOTER, PlayerTrait.REFUSES_DEFENSIVE_WORK));
    }

    @Test
    void tacticalShadowIsRejectedOutsideItsEligiblePositions() {
        List<RuntimeLineupSlot> slots = validSlots();
        FormationData shadowDefender = formation(2L, null, "Defend", List.of());
        shadowDefender.setShadow(true);
        slots.set(1, replaceFormation(slots.get(1), shadowDefender));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));

        assertThatThrownBy(() -> factory.build(tactic("Balanced"), slots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHADOW").hasMessageContaining("DC");
    }

    @Test
    void persistentStayForwardPlayerIsAlwaysShadowEvenWithoutSavedSelection() {
        List<RuntimeLineupSlot> slots = validSlots();
        slots.get(10).player().setStayForward(true);
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));

        var striker = factory.build(tactic("Balanced"), slots).lineup().stream()
                .filter(player -> player.playerId() == 11L).findFirst().orElseThrow();

        assertThat(striker.traits()).containsExactly(PlayerTrait.REFUSES_DEFENSIVE_WORK);
    }

    @Test
    void collectionsAndMutableSourceEntitiesCannotChangeBuiltInput() {
        List<RuntimeLineupSlot> slots = validSlots();
        ArrayList<String> instructions = new ArrayList<>(List.of("Stay Forward"));
        slots.set(10, replaceFormation(slots.get(10), formation(11L, null, "Support", instructions)));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(slots));
        CanonicalRuntimeTeamInput result = factory.build(tactic("Balanced"), slots);
        instructions.clear();
        slots.get(10).player().setFitness(1.0);
        slots.get(10).skills().setFinishing(1);

        var player = result.lineup().get(10);
        assertThat(result.tacticalContexts().get(11L).playerInstructions()).containsExactly("Stay Forward");
        assertThat(player.fitness()).isEqualTo(90.0);
        assertThat(player.attributes().get(com.footballmanagergamesimulator.compartment.PlayerAttribute.FINISHING))
                .isNotEqualTo(1);
    }

    @Test
    void invalidLineupSizesGoalkeepersDuplicatesAndSkillsMismatchAreRejected() {
        List<RuntimeLineupSlot> ten = validSlots().subList(0, 10);
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), ten))
                .isInstanceOf(IllegalArgumentException.class);
        List<RuntimeLineupSlot> twelve = new ArrayList<>(validSlots());
        twelve.add(slot(12, PlayerPosition.ST, 2, null));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), twelve))
                .isInstanceOf(IllegalArgumentException.class);

        List<RuntimeLineupSlot> duplicate = validSlots();
        duplicate.set(1, slot(1, PlayerPosition.DC, 1, null));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), duplicate))
                .isInstanceOf(IllegalArgumentException.class);

        List<RuntimeLineupSlot> noGoalkeeper = validSlots();
        noGoalkeeper.set(0, slot(1, PlayerPosition.DC, 1, null));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(noGoalkeeper));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), noGoalkeeper))
                .isInstanceOf(IllegalArgumentException.class);
        List<RuntimeLineupSlot> twoGoalkeepers = validSlots();
        twoGoalkeepers.set(1, slot(2, PlayerPosition.GK, 2, null));
        when(capabilities.loadAll(anyCollection())).thenReturn(snapshots(twoGoalkeepers));
        assertThatThrownBy(() -> factory.build(tactic("Balanced"), twoGoalkeepers))
                .isInstanceOf(IllegalArgumentException.class);

        Human player = human(1);
        PlayerSkills mismatchedSkills = skills(999);
        assertThatThrownBy(() -> new RuntimeLineupSlot(player, mismatchedSkills, null, PlayerPosition.GK, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills player id");
    }

    private List<RuntimeLineupSlot> validSlots() {
        return new ArrayList<>(List.of(
                slot(1, PlayerPosition.GK, 1, null), slot(2, PlayerPosition.DC, 1, null),
                slot(3, PlayerPosition.DL, 1, null), slot(4, PlayerPosition.DR, 1, null),
                slot(5, PlayerPosition.DM, 1, null), slot(6, PlayerPosition.MC, 1, null),
                slot(7, PlayerPosition.ML, 1, null), slot(8, PlayerPosition.MR, 1, null),
                slot(9, PlayerPosition.AMC, 1, null), slot(10, PlayerPosition.AML, 1, null),
                slot(11, PlayerPosition.ST, 1, null)));
    }

    private static RuntimeLineupSlot slot(long id, PlayerPosition position, int occurrence, FormationData formation) {
        return new RuntimeLineupSlot(human(id), skills(id), formation, position, occurrence);
    }

    private static RuntimeLineupSlot replaceFormation(RuntimeLineupSlot slot, FormationData formation) {
        return new RuntimeLineupSlot(slot.player(), slot.skills(), formation, slot.usedPosition(), slot.occurrence());
    }

    private static FormationData formation(long playerId, String role, String duty, List<String> instructions) {
        FormationData data = new FormationData();
        data.setPlayerId(playerId);
        data.setRole(role);
        data.setDuty(duty);
        data.setInstructions(instructions);
        return data;
    }

    private static PersonalizedTactic tactic(String mentality) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality(mentality);
        tactic.setTempo("Higher");
        tactic.setPassingType("Short");
        tactic.setDefensiveLine("High");
        tactic.setPressing("High");
        tactic.setWidth("Wide");
        return tactic;
    }

    private static Map<Long, PlayerCapabilitySnapshot> snapshots(List<RuntimeLineupSlot> slots) {
        Map<Long, PlayerCapabilitySnapshot> result = new LinkedHashMap<>();
        for (RuntimeLineupSlot slot : slots) {
            result.put(slot.player().getId(), new PlayerCapabilitySnapshot(slot.player().getId(),
                    slot.usedPosition(), Map.of(slot.usedPosition(), 20), Map.of(), 8, 20,
                    false, true, true));
        }
        return result;
    }

    private static Human human(long id) {
        Human human = new Human();
        human.setId(id);
        human.setFitness(90.0);
        human.setMorale(70.0);
        return human;
    }

    private static PlayerSkills skills(long playerId) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPlayerId(playerId);
        skills.setPosition("ST");
        skills.setFinishing(15);
        return skills;
    }
}
