package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.chairman.mandate.EffectiveChairmanMandate;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The assistant / automatic eleven ranks players by rating × the match's fitness factor. */
class TacticControllerFatigueSelectionTest {

    private static final long STAR_KEEPER = 1L;
    private static final long SECOND_KEEPER = 2L;

    @Test
    void tiredStarterMakesWayForAFresherSquadPlayer() {
        // 150 at fitness 40 is worth 150 × 0.70 = 105; the fresh 120 is worth 120.
        List<FormationData> picks = controller(squad(40.0)).askAssistant(10L, "442");
        assertThat(starterAt(picks, 27)).isEqualTo(SECOND_KEEPER);
        assertThat(benchIds(picks)).contains(STAR_KEEPER);
    }

    @Test
    void slightlyTiredStarterKeepsHisPlaceWhenHeIsStillTheBetterChoice() {
        // Fitness 70: 150 × 0.925 = 138.75, still above the fresh 120.
        assertThat(starterAt(controller(squad(70.0)).askAssistant(10L, "442"), 27)).isEqualTo(STAR_KEEPER);
    }

    @Test
    void freshSquadIsPickedByRatingAlone() {
        // At or above 80 the factor is exactly 1.0, whatever the reserve.
        assertThat(starterAt(controller(squad(80.0)).askAssistant(10L, "442"), 27)).isEqualTo(STAR_KEEPER);
        assertThat(starterAt(controller(squad(95.0)).askAssistant(10L, "442"), 27)).isEqualTo(STAR_KEEPER);
    }

    @Test
    void automaticElevenUsesTheSameFactor() {
        assertThat(controller(squad(40.0)).getBestEleven("10", "442"))
                .extracting(view -> view.getId()).contains(SECOND_KEEPER).doesNotContain(STAR_KEEPER);
        assertThat(controller(squad(85.0)).getBestEleven("10", "442"))
                .extracting(view -> view.getId()).contains(STAR_KEEPER).doesNotContain(SECOND_KEEPER);
    }

    private static long starterAt(List<FormationData> picks, int positionIndex) {
        return picks.stream().filter(fd -> fd.getPositionIndex() == positionIndex)
                .findFirst().orElseThrow().getPlayerId();
    }

    private static List<Long> benchIds(List<FormationData> picks) {
        return picks.stream().filter(fd -> fd.getPositionIndex() >= 30).map(FormationData::getPlayerId).toList();
    }

    /** A 4-4-2 squad with two keepers: the star at {@code starFitness}, the second keeper fresh. */
    private static List<Human> squad(double starFitness) {
        List<Human> squad = new ArrayList<>();
        squad.add(player(STAR_KEEPER, "GK", 150, starFitness));
        squad.add(player(SECOND_KEEPER, "GK", 120, 100));
        String[] outfield = {"DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST"};
        for (int i = 0; i < outfield.length; i++) squad.add(player(10L + i, outfield[i], 140, 100));
        return squad;
    }

    private static Human player(long id, String position, double rating, double fitness) {
        Human player = new Human();
        player.setId(id);
        player.setTeamId(10L);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setPosition(position);
        player.setRating(rating);
        player.setFitness(fitness);
        player.setMorale(70);
        return player;
    }

    private static TacticController controller(List<Human> squad) {
        Team team = new Team();
        team.setId(10L);
        team.setName("Test");
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        MatchSimulationOrchestrator availability = mock(MatchSimulationOrchestrator.class);
        CoachPermissionService permissions = mock(CoachPermissionService.class);
        ChairmanTacticalMandateEnforcementService mandates = mock(ChairmanTacticalMandateEnforcementService.class);
        com.footballmanagergamesimulator.service.NationService nations =
                mock(com.footballmanagergamesimulator.service.NationService.class);
        when(teams.findById(10L)).thenReturn(Optional.of(team));
        when(humans.findAllByTeamIdAndTypeId(10L, TypeNames.PLAYER_TYPE)).thenReturn(squad);
        when(availability.roundUnavailableIds(10L)).thenReturn(Set.of());
        when(permissions.lockedSlots(10L)).thenReturn(List.of());
        when(mandates.effectiveFormation(anyLong(), anyString())).thenReturn("442");
        when(mandates.mandate(10L)).thenReturn(EffectiveChairmanMandate.absent());
        when(mandates.resolvedLockedSlots(anyLong(), anyString(), any(), any())).thenReturn(List.of());
        when(nations.infoForTeam(10L)).thenReturn(
                new com.footballmanagergamesimulator.service.NationService.NationInfo(1L, "Testland", "te"));

        TacticController controller = new TacticController();
        ReflectionTestUtils.setField(controller, "teamRepository", teams);
        ReflectionTestUtils.setField(controller, "humanRepository", humans);
        ReflectionTestUtils.setField(controller, "matchSimulationOrchestrator", availability);
        ReflectionTestUtils.setField(controller, "coachPermissionService", permissions);
        ReflectionTestUtils.setField(controller, "mandateEnforcement", mandates);
        ReflectionTestUtils.setField(controller, "nationService", nations);
        ReflectionTestUtils.setField(controller, "playerPersonalityService",
                new com.footballmanagergamesimulator.service.PlayerPersonalityService());
        ReflectionTestUtils.setField(controller, "tacticService", new TacticService());
        // compartmentEngineConfig left null: the shipped defaults (fresh 80, sensitivity 0.75, floor 0.60).
        return controller;
    }
}
