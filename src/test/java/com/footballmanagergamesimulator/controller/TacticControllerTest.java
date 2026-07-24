package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandate;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.PlayerValueService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class TacticControllerTest {
    @Test
    void bestElevenUsesCurrentChairmanFormationInsteadOfRequestedFormation() {
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        TacticService tactics = mock(TacticService.class);
        MatchSimulationOrchestrator availability = mock(MatchSimulationOrchestrator.class);
        CoachPermissionService permissions = mock(CoachPermissionService.class);
        ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("433");
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));
        when(teams.findById(10L)).thenReturn(Optional.of(new Team()));
        when(availability.roundUnavailableIds(10L)).thenReturn(java.util.Set.of());
        when(permissions.lockedSlots(10L)).thenReturn(java.util.List.of());
        when(tactics.isKnownFormation("433")).thenReturn(true);
        when(tactics.getAllExistingTactics()).thenReturn(java.util.List.of("433"));
        when(tactics.getFormationGridIndicesExact("433")).thenReturn(new int[]{1, 2, 3, 10, 12, 14, 20, 21, 23, 24, 27});
        when(tactics.getRoomInTeamByTactic("433")).thenReturn(Map.of("GK", 1));
        when(tactics.getValueForTacticDisplay(anyString())).thenReturn(0);

        TacticController controller = new TacticController();
        ReflectionTestUtils.setField(controller, "teamRepository", teams);
        ReflectionTestUtils.setField(controller, "humanRepository", humans);
        ReflectionTestUtils.setField(controller, "tacticService", tactics);
        ReflectionTestUtils.setField(controller, "matchSimulationOrchestrator", availability);
        ReflectionTestUtils.setField(controller, "coachPermissionService", permissions);
        ReflectionTestUtils.setField(controller, "mandateEnforcement",
                new ChairmanTacticalMandateEnforcementService(mandates, humans, tactics));

        controller.getBestEleven("10", "442");

        verify(tactics).getRoomInTeamByTactic("433");
        verify(tactics, never()).getRoomInTeamByTactic("442");
    }

    @Test
    void getFormationShowsPlayerOnlyMandateWhenNoPersonalizedTacticExists() {
        HumanRepository humans = mock(HumanRepository.class);
        PersonalizedTacticRepository personalized = mock(PersonalizedTacticRepository.class);
        ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
        CoachPermissionService permissions = mock(CoachPermissionService.class);
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.replaceSlots(java.util.List.of(new com.footballmanagergamesimulator.chairman.mandate.MandateSlot(1, 99L)));
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));
        when(personalized.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.empty());
        when(permissions.lockedSlots(10L)).thenReturn(java.util.List.of());

        java.util.List<Human> squad = new java.util.ArrayList<>();
        for (long id = 1; id <= 11; id++) squad.add(player(id == 1 ? 99L : id));
        when(humans.findAllByTeamIdAndTypeId(10L, TypeNames.PLAYER_TYPE)).thenReturn(squad);
        when(humans.findById(anyLong())).thenAnswer(invocation -> squad.stream()
                .filter(player -> player.getId() == (Long) invocation.getArgument(0)).findFirst());

        TacticController controller = new TacticController();
        ReflectionTestUtils.setField(controller, "humanRepository", humans);
        ReflectionTestUtils.setField(controller, "personalizedTacticRepository", personalized);
        ReflectionTestUtils.setField(controller, "coachPermissionService", permissions);
        ReflectionTestUtils.setField(controller, "tacticService", new TacticService());
        ReflectionTestUtils.setField(controller, "mandateEnforcement",
                new ChairmanTacticalMandateEnforcementService(mandates, humans, new TacticService()));

        PersonalizedTacticView view = controller.getFormation("10");

        assertThat(view).isNotNull();
        assertThat(view.getFormationDataList()).extracting(com.footballmanagergamesimulator.frontend.FormationData::getPlayerId)
                .contains(99L);
    }

    @Test
    void teamViewCopiesImmutableCompletedFormationBeforeAddingBench() throws Exception {
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        PersonalizedTacticRepository personalized = mock(PersonalizedTacticRepository.class);
        ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
        CoachPermissionService permissions = mock(CoachPermissionService.class);
        MatchSimulationOrchestrator availability = mock(MatchSimulationOrchestrator.class);
        NationService nations = mock(NationService.class);
        PlayerValueService values = mock(PlayerValueService.class);
        TacticService tactics = new TacticService();

        Team team = new Team();
        team.setId(10L);
        team.setName("Test");
        when(teams.findById(10L)).thenReturn(Optional.of(team));
        when(nations.infoForTeam(10L)).thenReturn(new NationService.NationInfo(1L, "Testland", "te"));
        when(values.fitnessFactor(anyDouble())).thenReturn(1.0);
        when(permissions.lockedSlots(10L)).thenReturn(List.of());
        when(availability.roundUnavailableIds(10L)).thenReturn(Set.of(11L));

        List<Human> squad = new ArrayList<>();
        for (long id = 1; id <= 11; id++) squad.add(player(id));
        squad.add(player(99L));
        when(humans.findAllByTeamIdAndTypeId(10L, TypeNames.PLAYER_TYPE)).thenReturn(squad);
        when(humans.findAllByTeamIdAndTypeId(10L, TypeNames.MANAGER_TYPE)).thenReturn(List.of());
        when(humans.findById(anyLong())).thenAnswer(invocation -> squad.stream()
                .filter(value -> value.getId() == (Long) invocation.getArgument(0)).findFirst());

        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("442");
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));

        int[] grid = {1, 3, 10, 11, 13, 14, 20, 21, 23, 24, 27};
        List<FormationData> savedEntries = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) savedEntries.add(data(grid[i], i + 1L));
        PersonalizedTactic saved = new PersonalizedTactic();
        saved.setTeamId(10L);
        saved.setTactic("442");
        saved.setFirst11(new ObjectMapper().writeValueAsString(savedEntries));
        when(personalized.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.of(saved));

        TacticController controller = new TacticController();
        ReflectionTestUtils.setField(controller, "teamRepository", teams);
        ReflectionTestUtils.setField(controller, "humanRepository", humans);
        ReflectionTestUtils.setField(controller, "personalizedTacticRepository", personalized);
        ReflectionTestUtils.setField(controller, "tacticService", tactics);
        ReflectionTestUtils.setField(controller, "matchSimulationOrchestrator", availability);
        ReflectionTestUtils.setField(controller, "coachPermissionService", permissions);
        ReflectionTestUtils.setField(controller, "nationService", nations);
        ReflectionTestUtils.setField(controller, "playerValueService", values);
        ReflectionTestUtils.setField(controller, "mandateEnforcement",
                new ChairmanTacticalMandateEnforcementService(mandates, humans, tactics));

        ResponseEntity<?> response = controller.getTeamTacticView(10L);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        PersonalizedTacticView managerView = (PersonalizedTacticView) body.get("managerTactic");
        List<FormationData> effective = managerView.getFormationDataList();
        List<FormationData> starters = effective.stream().filter(value -> value.getPositionIndex() < 30).toList();

        assertThat(managerView.getTactic()).isEqualTo("442");
        assertThat(starters).hasSize(11);
        assertThat(starters).extracting(FormationData::getPlayerId).doesNotContain(11L)
                .contains(99L).doesNotHaveDuplicates();
        assertThat(effective).extracting(FormationData::getPositionIndex).doesNotHaveDuplicates();
        assertThat(effective.stream().filter(value -> value.getPositionIndex() >= 30).count()).isLessThanOrEqualTo(7);
        assertThat(starters).allMatch(value -> Arrays.stream(grid).anyMatch(index -> index == value.getPositionIndex()));
    }

    private static Human player(long id) {
        Human player = new Human();
        player.setId(id);
        player.setTeamId(10L);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setPosition("MC");
        player.setRating(10);
        return player;
    }

    private static FormationData data(int position, long playerId) {
        FormationData data = new FormationData();
        data.setPositionIndex(position);
        data.setPlayerId(playerId);
        return data;
    }
}
