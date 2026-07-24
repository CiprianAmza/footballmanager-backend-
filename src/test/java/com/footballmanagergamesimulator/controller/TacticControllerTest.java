package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandate;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
}
