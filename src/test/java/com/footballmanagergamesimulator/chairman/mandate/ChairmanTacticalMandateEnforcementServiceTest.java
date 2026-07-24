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
