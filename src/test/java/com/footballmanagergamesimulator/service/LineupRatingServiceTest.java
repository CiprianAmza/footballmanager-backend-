package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandate;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.chairman.mandate.MandateSlot;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchPlayerRatingRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LineupRatingServiceTest {
    @Test
    void absentMandateWithoutSavedLineupUsesLegacyBestElevenSlotsNotAssistant() {
        PersonalizedTacticRepository tactics = mock(PersonalizedTacticRepository.class);
        ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
        MatchSimulationOrchestrator availability = mock(MatchSimulationOrchestrator.class);
        TacticController controller = mock(TacticController.class);
        PlayerSkillsRepository skills = mock(PlayerSkillsRepository.class);
        PlayerValueService values = mock(PlayerValueService.class);
        PlayerView view = new PlayerView();
        view.setId(42L);
        view.setPosition("MC");
        view.setRating(12);
        when(tactics.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.empty());
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        when(availability.roundUnavailableIds(10L)).thenReturn(Set.of());
        when(controller.getBestElevenWithSlots("10", "442"))
                .thenReturn(List.of(new TacticController.StarterSlot(view, "MC")));
        when(skills.findAllByPlayerIdIn(List.of(42L))).thenReturn(List.of());
        when(values.evaluatePlayer(12.0, "MC", "MC", 0.0, 0.0)).thenReturn(12.0);

        LineupRatingService service = new LineupRatingService();
        ReflectionTestUtils.setField(service, "personalizedTacticRepository", tactics);
        ReflectionTestUtils.setField(service, "mandateEnforcement",
                new ChairmanTacticalMandateEnforcementService(mandates, mock(HumanRepository.class), new TacticService()));
        ReflectionTestUtils.setField(service, "matchSimulationOrchestrator", availability);
        ReflectionTestUtils.setField(service, "tacticController", controller);
        ReflectionTestUtils.setField(service, "playerSkillsRepository", skills);
        ReflectionTestUtils.setField(service, "playerValueService", values);

        assertThat(service.computePlayerRatings(10L, "442")).extracting(LineupRatingService.PlayerRatingLine::playerId)
                .containsExactly(42L);
        verify(controller).getBestElevenWithSlots("10", "442");
        verify(controller, never()).askAssistant(anyLong(), anyString());
    }

    @Test
    void savedLineupIsReResolvedWhenChairmanChangesThePlayerAndSnapshotUsesTheSameXI() throws Exception {
        PersonalizedTacticRepository tactics = mock(PersonalizedTacticRepository.class);
        ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        PlayerSkillsRepository skills = mock(PlayerSkillsRepository.class);
        MatchSimulationOrchestrator availability = mock(MatchSimulationOrchestrator.class);
        CoachPermissionService permissions = mock(CoachPermissionService.class);
        TacticController controller = mock(TacticController.class);
        PlayerValueService values = mock(PlayerValueService.class);
        PlayerRoleService roles = mock(PlayerRoleService.class);
        PlayerInstructionService instructions = mock(PlayerInstructionService.class);

        when(availability.roundUnavailableIds(10L)).thenReturn(Set.of());
        when(permissions.lockedSlots(10L)).thenReturn(List.of());
        when(mandates.findByTeamId(10L)).thenAnswer(invocation -> Optional.of(mandate()));
        when(humans.findById(anyLong())).thenAnswer(invocation -> Optional.of(player(invocation.getArgument(0))));
        when(skills.findPlayerSkillsByPlayerId(anyLong())).thenReturn(Optional.empty());
        when(values.familiarityFactor(anyString(), anyString())).thenReturn(1.0);
        when(values.moraleFactor(anyDouble())).thenReturn(1.0);
        when(values.fitnessFactor(anyDouble())).thenReturn(1.0);
        when(instructions.computeInstructionMultiplier(any(), anyString(), anyString())).thenReturn(1.0);

        PersonalizedTactic saved = new PersonalizedTactic();
        saved.setTeamId(10L);
        saved.setTactic("442");
        List<FormationData> old = new ArrayList<>();
        int[] grid = {1, 3, 10, 11, 13, 14, 20, 21, 23, 24, 27};
        for (int index = 0; index < grid.length; index++) old.add(data(grid[index], 100L + index));
        saved.setFirst11(new ObjectMapper().writeValueAsString(old));
        when(tactics.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.of(saved));

        LineupRatingService service = new LineupRatingService();
        ReflectionTestUtils.setField(service, "personalizedTacticRepository", tactics);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "playerSkillsRepository", skills);
        ReflectionTestUtils.setField(service, "competitionRepository", mock(CompetitionRepository.class));
        ReflectionTestUtils.setField(service, "teamRepository", mock(TeamRepository.class));
        ReflectionTestUtils.setField(service, "scorerRepository", mock(ScorerRepository.class));
        ReflectionTestUtils.setField(service, "scorerLeaderboardRepository", mock(ScorerLeaderboardRepository.class));
        ReflectionTestUtils.setField(service, "matchPlayerRatingRepository", mock(MatchPlayerRatingRepository.class));
        ReflectionTestUtils.setField(service, "tacticService", new TacticService());
        ReflectionTestUtils.setField(service, "mandateEnforcement",
                new ChairmanTacticalMandateEnforcementService(mandates, humans, new TacticService()));
        ReflectionTestUtils.setField(service, "coachPermissionService", permissions);
        ReflectionTestUtils.setField(service, "playerValueService", values);
        ReflectionTestUtils.setField(service, "playerRoleService", roles);
        ReflectionTestUtils.setField(service, "playerInstructionService", instructions);
        ReflectionTestUtils.setField(service, "tacticController", controller);
        ReflectionTestUtils.setField(service, "matchSimulationOrchestrator", availability);

        List<LineupRatingService.PlayerRatingLine> ratings = service.computePlayerRatings(10L, "442");
        List<FormationData> snapshot = ReflectionTestUtils.invokeMethod(service,
                "buildFormationSnapshot", 10L, "442");

        assertThat(ratings).extracting(LineupRatingService.PlayerRatingLine::playerId).contains(999L)
                .doesNotContain(100L);
        assertThat(snapshot).filteredOn(data -> data.getPositionIndex() < 30)
                .extracting(FormationData::getPlayerId).contains(999L).doesNotContain(100L);
        verify(controller, never()).askAssistant(anyLong(), anyString());
    }

    private static ChairmanTacticalMandate mandate() {
        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("442");
        mandate.replaceSlots(List.of(new MandateSlot(1, 999L)));
        return mandate;
    }

    private static FormationData data(int position, long playerId) {
        FormationData data = new FormationData();
        data.setPositionIndex(position);
        data.setPlayerId(playerId);
        return data;
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
}
