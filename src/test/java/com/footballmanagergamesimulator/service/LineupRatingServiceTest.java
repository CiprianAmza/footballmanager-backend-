package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandate;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.chairman.mandate.MandateSlot;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Scorer;
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
    void canonicalRatingSnapshotUsesTheExactMatchPlanSquad() {
        LineupRatingService service = spy(new LineupRatingService());
        ReflectionTestUtils.setField(service, "tacticService", new TacticService());
        doNothing().when(service).persistPlayerRatings(
                anyLong(), anyInt(), anyInt(), anyLong(), anyString(), anyList(), anyList());
        Contributor actualStarter = contributor(501L, "MC");
        Contributor actualBench = contributor(502L, "ST");
        Lineup exact = new Lineup(List.of(actualStarter), List.of(actualBench), List.of());

        service.persistPlayerRatings(1L, 2, 3, 10L, "541", exact);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<TacticController.StarterSlot>> starters =
                org.mockito.ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PlayerView>> bench =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service).persistPlayerRatings(eq(1L), eq(2), eq(3), eq(10L), eq("541"),
                starters.capture(), bench.capture());
        assertThat(starters.getValue()).extracting(slot -> slot.player().getId())
                .containsExactly(501L);
        assertThat(starters.getValue()).extracting(TacticController.StarterSlot::usedPosition)
                .containsExactly("MC");
        assertThat(bench.getValue()).extracting(PlayerView::getId).containsExactly(502L);
    }

    @Test
    void canonicalScorersContainOnlyPlayersWhoActuallyAppearedInMatchPlan() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        PersonalizedTacticRepository tactics = mock(PersonalizedTacticRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        GameStateService gameState = mock(GameStateService.class);
        MatchSimulationService simulation = mock(MatchSimulationService.class);
        PlayerMatchStatService playerStats = mock(PlayerMatchStatService.class);
        when(competitions.findTypeIdById(1L)).thenReturn(1L);
        when(tactics.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.empty());
        when(humans.findById(anyLong())).thenReturn(Optional.empty());
        when(gameState.currentSeason()).thenReturn(2);

        LineupRatingService service = new LineupRatingService();
        ReflectionTestUtils.setField(service, "competitionRepository", competitions);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "personalizedTacticRepository", tactics);
        ReflectionTestUtils.setField(service, "humanRepository", humans);
        ReflectionTestUtils.setField(service, "scorerRepository", scorers);
        ReflectionTestUtils.setField(service, "scorerLeaderboardRepository", mock(ScorerLeaderboardRepository.class));
        ReflectionTestUtils.setField(service, "competitionService", mock(CompetitionService.class));
        ReflectionTestUtils.setField(service, "matchSimulationService", simulation);
        ReflectionTestUtils.setField(service, "gameStateService", gameState);
        ReflectionTestUtils.setField(service, "playerMatchStatService", playerStats);

        Contributor starter = contributor(501L, "MC");
        Contributor playedSub = contributor(502L, "ST");
        Contributor unusedSub = contributor(503L, "DC");
        Lineup exact = new Lineup(List.of(starter), List.of(playedSub, unusedSub),
                List.of(new Lineup.SubMove(0, 60, starter.playerId(), playedSub)));

        service.getScorersForTeam(10L, 20L, 0, 0, "541", 1L, 3,
                Map.of(), exact);

        org.mockito.ArgumentCaptor<Scorer> persisted = org.mockito.ArgumentCaptor.forClass(Scorer.class);
        verify(scorers, times(2)).save(persisted.capture());
        assertThat(persisted.getAllValues()).extracting(Scorer::getPlayerId)
                .containsExactlyInAnyOrder(501L, 502L)
                .doesNotContain(503L);
        assertThat(persisted.getAllValues()).filteredOn(Scorer::isSubstitute)
                .extracting(Scorer::getPlayerId).containsExactly(502L);
    }

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

    @Test
    void activeRuntimeRatingsAndSnapshotUseTheSameReplacementForUnavailableSavedPlayer() throws Exception {
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

        ChairmanTacticalMandate mandate = new ChairmanTacticalMandate();
        mandate.setTeamId(10L);
        mandate.setRequiredFormation("442");
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(mandate));
        when(availability.roundUnavailableIds(10L)).thenReturn(Set.of(105L));
        when(permissions.lockedSlots(10L)).thenReturn(List.of());
        when(humans.findById(anyLong())).thenAnswer(invocation -> Optional.of(player(invocation.getArgument(0))));
        when(skills.findPlayerSkillsByPlayerId(anyLong())).thenReturn(Optional.empty());
        when(values.familiarityFactor(anyString(), anyString())).thenReturn(1.0);
        when(values.moraleFactor(anyDouble())).thenReturn(1.0);
        when(values.fitnessFactor(anyDouble())).thenReturn(1.0);
        when(instructions.computeInstructionMultiplier(any(), anyString(), anyString())).thenReturn(1.0);

        int[] grid = {1, 3, 10, 11, 13, 14, 20, 21, 23, 24, 27};
        List<FormationData> savedEntries = new ArrayList<>();
        List<FormationData> assistantEntries = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) {
            savedEntries.add(data(grid[i], 100L + i));
            assistantEntries.add(data(grid[i], i == 5 ? 900L : 100L + i));
        }
        PersonalizedTactic saved = new PersonalizedTactic();
        saved.setTeamId(10L);
        saved.setTactic("442");
        saved.setFirst11(new ObjectMapper().writeValueAsString(savedEntries));
        when(tactics.findPersonalizedTacticByTeamId(10L)).thenReturn(Optional.of(saved));
        when(controller.askAssistant(10L, "442")).thenReturn(assistantEntries);

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

        List<Long> ratingIds = service.computePlayerRatings(10L, "442").stream()
                .map(LineupRatingService.PlayerRatingLine::playerId).toList();
        List<Long> snapshotIds = ReflectionTestUtils.<List<FormationData>>invokeMethod(service,
                        "buildFormationSnapshot", 10L, "442").stream()
                .filter(value -> value.getPositionIndex() < 30)
                .map(FormationData::getPlayerId).toList();

        assertThat(ratingIds).hasSize(11).doesNotContain(105L).contains(900L).doesNotHaveDuplicates();
        assertThat(snapshotIds).containsExactlyElementsOf(ratingIds)
                .doesNotContain(105L).contains(900L).doesNotHaveDuplicates();
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

    private static Contributor contributor(long id, String position) {
        return new Contributor(id, "P" + id, position, 100, 10, 10, 10,
                100, false, false);
    }
}
