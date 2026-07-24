package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ChairmanTacticalMandateEnforcementServiceTest {
    private final ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final ChairmanTacticalMandateEnforcementService service =
            new ChairmanTacticalMandateEnforcementService(mandates, humans, new TacticService());
    private final Map<Long, Human> squad = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(mandates.findByTeamId(anyLong())).thenReturn(Optional.empty());
        when(humans.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(squad.get(invocation.getArgument(0))));
    }

    @Test
    void absentMandatePreservesLegacyFormationAndDoesNotMutateInput() {
        List<FormationData> submitted = new ArrayList<>(List.of(data(1, 10), data(3, 11)));
        List<FormationData> result = service.enforceFormation(10L, "442", submitted, List.of(), Set.of(), false);

        assertThat(result).extracting(FormationData::getPlayerId).containsExactly(10L, 11L);
        assertThat(submitted).extracting(FormationData::getPositionIndex).containsExactly(1, 3);
        assertThatThrownBy(() -> result.add(data(5, 12))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void absentMandateRuntimeStillFiltersUnavailableWithLegacySemantics() {
        List<FormationData> result = service.enforceFormation(10L, "442",
                List.of(data(1, 10L)), List.of(), Set.of(10L), true);

        assertThat(result).isEmpty();
    }

    @Test
    void absentMandateEditPreservesUnavailableWithLegacySemantics() {
        List<FormationData> result = service.enforceFormation(10L, "442",
                List.of(data(1, 10L)), List.of(), Set.of(10L), false);

        assertThat(result).extracting(FormationData::getPlayerId).containsExactly(10L);
    }

    @Test
    void chairmanFormationFiltersOldExclusiveSlotAndLocksWinOverLegacy() {
        squad.put(100L, player(100L));
        squad.put(101L, player(101L));
        squad.put(102L, player(102L));
        squad.put(103L, player(103L));
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("433");
        mandate.replaceSlots(List.of(new MandateSlot(2, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        List<FormationData> submitted = List.of(data(5, 101L), data(1, 102L)); // 5 belongs only to old 442 here
        List<FormationData> result = service.enforceFormation(10L, "442", submitted,
                List.of(new CoachPermissionService.LockedSlot(2, 102L)), Set.of(), false);

        assertThat(result).extracting(FormationData::getPositionIndex).containsExactly(1, 2);
        assertThat(result).extracting(FormationData::getPlayerId).containsExactly(102L, 100L);
        assertThat(submitted).extracting(FormationData::getPositionIndex).containsExactly(5, 1);
    }

    @Test
    void unavailableChairmanPlayerIsOmittedOnlyAtRuntime() {
        squad.put(100L, player(100L));
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("433");
        mandate.replaceSlots(List.of(new MandateSlot(2, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        assertThat(service.eligibleSlots(10L, Set.of(100L))).isEmpty();
        squad.clear();
        assertThatThrownBy(() -> service.enforceFormation(10L, "433", List.of(), List.of(), Set.of(), false))
                .hasFieldOrPropertyWithValue("code", "MANDATED_PLAYER_NOT_IN_TEAM");
    }

    @Test
    void chairmanLegacyAndManagerPrecedencePreservesExactLockMetadata() {
        squad.put(100L, player(100L));
        squad.put(101L, player(101L));
        squad.put(102L, player(102L));
        squad.put(103L, player(103L));
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("433");
        mandate.replaceSlots(List.of(new MandateSlot(2, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        FormationData chairman = data(2, 100L);
        chairman.setRole("Advanced Playmaker");
        chairman.setDuty("Attack");
        chairman.setInstructions(List.of("Shoot More Often"));
        List<FormationData> submitted = new ArrayList<>(List.of(
                chairman, data(2, 101L), data(1, 102L), data(3, 103L)));
        List<FormationData> result = service.enforceFormation(10L, "433", submitted,
                List.of(new CoachPermissionService.LockedSlot(2, 102L)), Set.of(), false);

        assertThat(result).filteredOn(value -> value.getPlayerId() == 100L).singleElement()
                .satisfies(value -> {
                    assertThat(value.getRole()).isEqualTo("Advanced Playmaker");
                    assertThat(value.getDuty()).isEqualTo("Attack");
                    assertThat(value.getInstructions()).containsExactly("Shoot More Often");
                });
        assertThat(result).extracting(FormationData::getPlayerId).contains(100L, 102L, 103L).doesNotContain(101L);
        assertThat(submitted).extracting(FormationData::getPositionIndex).containsExactly(2, 2, 1, 3);
    }

    @Test
    void chairmanLockOutsideEffectiveFormationIsRejectedWithoutRelocation() {
        squad.put(100L, player(100L));
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("433");
        mandate.replaceSlots(List.of(new MandateSlot(5, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        assertThatThrownBy(() -> service.enforceFormation(10L, "433", List.of(), List.of(), Set.of(), false))
                .hasFieldOrPropertyWithValue("code", "MANDATE_SLOT_NOT_IN_FORMATION");
    }

    @Test
    void limitsStartersAndBenchBySlotRangeEvenWhenBenchArrivesFirst() {
        List<FormationData> submitted = new ArrayList<>();
        for (int slot = 30; slot <= 38; slot++) submitted.add(data(slot, 1000L + slot));
        for (int slot = 0; slot < 15; slot++) submitted.add(data(slot, 2000L + slot));

        List<FormationData> result = service.enforceFormation(10L, "442", submitted, List.of(), Set.of(), true);

        assertThat(result).filteredOn(value -> value.getPositionIndex() < 30).hasSize(11);
        assertThat(result).filteredOn(value -> value.getPositionIndex() >= 30 && value.getPositionIndex() <= 36).hasSize(7);
        assertThat(result).allMatch(value -> value.getPositionIndex() <= 36);
    }

    @Test
    void activeMandateRejectsForeignManagerSelectionAtEditTime() {
        squad.put(100L, player(100L));
        Human foreign = player(200L);
        foreign.setTeamId(11L);
        squad.put(200L, foreign);
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("442");
        mandate.replaceSlots(List.of(new MandateSlot(1, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        assertThatThrownBy(() -> service.enforceFormation(10L, "442", List.of(data(3, 200L)),
                List.of(), Set.of(), false))
                .hasFieldOrPropertyWithValue("code", "MANAGER_XI_INVALID");
    }

    @Test
    void activeMandateRuntimeOmitsForeignStaffRetiredAndUnavailablePlayers() {
        Human locked = player(100L);
        Human foreign = player(200L);
        foreign.setTeamId(11L);
        Human staff = player(201L);
        staff.setTypeId(TypeNames.MANAGER_TYPE);
        Human retired = player(202L);
        retired.setRetired(true);
        squad.put(100L, locked);
        squad.put(200L, foreign);
        squad.put(201L, staff);
        squad.put(202L, retired);
        Human unavailable = player(203L);
        squad.put(203L, unavailable);
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("442");
        mandate.replaceSlots(List.of(new MandateSlot(1, 100L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        List<FormationData> result = service.enforceFormation(10L, "442", List.of(
                data(3, 200L), data(4, 201L), data(5, 202L), data(6, 203L)),
                List.of(), Set.of(203L), true);

        assertThat(result).extracting(FormationData::getPlayerId)
                .containsExactly(100L).doesNotContain(200L, 201L, 202L, 203L);
    }

    @Test
    void completionPromotesBenchPlayerBeforePreservedBenchAndFillsBenchWithoutDuplicates() {
        List<FormationData> preserved = new ArrayList<>();
        for (int slot = 0; slot < 10; slot++) preserved.add(data(slot, 100L + slot));
        preserved.add(data(30, 999L));
        List<FormationData> assistant = new ArrayList<>();
        for (int slot = 0; slot < 10; slot++) assistant.add(data(slot, 300L + slot));
        assistant.add(data(10, 999L));
        for (int slot = 30; slot <= 36; slot++) assistant.add(data(slot, 400L + slot));

        List<FormationData> result = service.completeFormation(preserved, assistant);

        assertThat(result).filteredOn(value -> value.getPositionIndex() < 30).hasSize(11);
        assertThat(result).filteredOn(value -> value.getPlayerId() == 999L)
                .singleElement().extracting(FormationData::getPositionIndex).isEqualTo(10);
        assertThat(result).filteredOn(value -> value.getPositionIndex() >= 30).hasSize(7);
        assertThat(result).extracting(FormationData::getPositionIndex).doesNotHaveDuplicates();
        assertThat(result).extracting(FormationData::getPlayerId).doesNotHaveDuplicates();
    }

    @Test
    void duplicateManagerPlayerDoesNotPoisonLaterAssistantCompletion() {
        List<FormationData> preserved = List.of(data(0, 500L), data(1, 500L));
        List<FormationData> assistant = new ArrayList<>();
        for (int slot = 0; slot < 11; slot++) assistant.add(data(slot, 600L + slot));

        List<FormationData> result = service.completeFormation(preserved, assistant);

        assertThat(result).extracting(FormationData::getPlayerId).doesNotHaveDuplicates();
        assertThat(result).extracting(FormationData::getPositionIndex).doesNotHaveDuplicates();
        assertThat(result).filteredOn(value -> value.getPositionIndex() < 30).hasSize(11);
    }

    private static FormationData data(int position, long playerId) {
        FormationData value = new FormationData();
        value.setPositionIndex(position);
        value.setPlayerId(playerId);
        return value;
    }

    private static Human player(long id) {
        Human value = new Human();
        value.setId(id);
        value.setTeamId(10L);
        value.setTypeId(TypeNames.PLAYER_TYPE);
        value.setPosition("MC");
        return value;
    }
}
