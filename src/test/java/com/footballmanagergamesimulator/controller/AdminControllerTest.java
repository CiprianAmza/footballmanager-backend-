package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.EuropeanFixturePreparationService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.ScorerLeaderboardSyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    @Test
    void adminLoginCreatesAnAdminSessionForTheSecuredEndpoints() {
        AdminController controller = new AdminController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<?> result = controller.login(
                Map.of("username", "admin", "password", "admin"), request, response);

        assertEquals(200, result.getStatusCode().value());
        assertNotNull(request.getSession(false));
        SecurityContext context = (SecurityContext) request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(context);
        assertEquals("admin", context.getAuthentication().getName());
        org.junit.jupiter.api.Assertions.assertTrue(context.getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())));
    }

    @Test
    void upcomingMatchesDoesNotRevealEuropeanDrawBeforeItsCalendarEvent() {
        AdminController controller = new AdminController();
        RoundRepository roundRepository = mock(RoundRepository.class);
        EuropeanFixturePreparationService preparationService = mock(EuropeanFixturePreparationService.class);
        CompetitionTeamInfoMatchRepository matchRepository = mock(CompetitionTeamInfoMatchRepository.class);
        CompetitionTeamInfoDetailRepository detailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        PredeterminedScoreRepository scoreRepository = mock(PredeterminedScoreRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        ReflectionTestUtils.setField(controller, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(controller, "europeanFixturePreparationService", preparationService);
        ReflectionTestUtils.setField(controller, "competitionTeamInfoMatchRepository", matchRepository);
        ReflectionTestUtils.setField(controller, "competitionTeamInfoDetailRepository", detailRepository);
        ReflectionTestUtils.setField(controller, "predeterminedScoreRepository", scoreRepository);
        ReflectionTestUtils.setField(controller, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(controller, "teamRepository", teamRepository);

        Round season = new Round();
        season.setSeason(2);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(season));
        when(request.getHeader("X-Admin-Token")).thenReturn("admin-token-2026");
        when(matchRepository.findAll()).thenReturn(List.of());
        when(detailRepository.findAll()).thenReturn(List.of());
        when(scoreRepository.findAllByConsumedFalse()).thenReturn(List.of());
        when(competitionRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAll()).thenReturn(List.of());

        ResponseEntity<?> response = controller.getUpcomingMatches(request);

        assertEquals(200, response.getStatusCode().value());
        verify(preparationService, never()).prepareNextFixturesForEachCompetition(2);
    }

    @Test
    void generatedPlayerKeepsTheExactAdminRating() {
        AdminController controller = new AdminController();
        HumanRepository humanRepository = mock(HumanRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        ScorerLeaderboardSyncService leaderboardSyncService = mock(ScorerLeaderboardSyncService.class);

        ReflectionTestUtils.setField(controller, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(controller, "playerSkillsRepository", playerSkillsRepository);
        ReflectionTestUtils.setField(controller, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(controller, "competitionService", new CompetitionService());
        ReflectionTestUtils.setField(controller, "scorerLeaderboardSyncService", leaderboardSyncService);

        Round season = new Round();
        season.setSeason(1);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(season));
        when(humanRepository.save(any(Human.class))).thenAnswer(invocation -> {
            Human player = invocation.getArgument(0);
            player.setId(42L);
            return player;
        });
        when(playerSkillsRepository.save(any(PlayerSkills.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.generatePlayer(Map.of(
                "name", "Maximum Player",
                "position", "MC",
                "age", 22,
                "rating", 300
        ));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(300.0, response.getBody().get("rating"));

        ArgumentCaptor<Human> playerCaptor = ArgumentCaptor.forClass(Human.class);
        verify(humanRepository, times(1)).save(playerCaptor.capture());
        Human savedPlayer = playerCaptor.getValue();
        assertEquals(300.0, savedPlayer.getRating());
        assertEquals(300, savedPlayer.getCurrentAbility());
        org.junit.jupiter.api.Assertions.assertTrue(savedPlayer.getPotentialAbility() > 300);
        verify(leaderboardSyncService).trackNewPlayer(savedPlayer);

        ArgumentCaptor<PlayerSkills> skillsCaptor = ArgumentCaptor.forClass(PlayerSkills.class);
        verify(playerSkillsRepository).save(skillsCaptor.capture());
        assertEquals(300.0, PlayerSkillsService.computeOverallRating(skillsCaptor.getValue()), 0.000001);
    }

    @Test
    void extendsEveryActivePlayerContractForOneTeam() {
        AdminController controller = new AdminController();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        HttpServletRequest request = adminRequest();
        ReflectionTestUtils.setField(controller, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(controller, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(controller, "roundRepository", roundRepository);

        Team team = new Team();
        team.setId(7L);
        team.setName("Shadows");
        Human currentDeal = contractedPlayer(10L, 7L, 6, false);
        Human expiredDeal = contractedPlayer(11L, 7L, 2, false);
        expiredDeal.setPreContractTeamId(9L);
        Human retired = contractedPlayer(12L, 7L, 4, true);
        when(teamRepository.findById(7L)).thenReturn(Optional.of(team));
        when(humanRepository.findAllByTeamIdAndTypeId(7L, 1L))
                .thenReturn(List.of(currentDeal, expiredDeal, retired));
        Round round = new Round();
        round.setSeason(3);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));

        ResponseEntity<?> response = controller.extendPlayerContracts(
                Map.of("teamId", 7, "allTeams", false, "seasons", 3), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(9, currentDeal.getContractEndSeason());
        assertEquals(6, expiredDeal.getContractEndSeason());
        assertEquals(4, retired.getContractEndSeason());
        assertEquals(0, expiredDeal.getPreContractTeamId());
        verify(humanRepository).saveAll(List.of(currentDeal, expiredDeal));
    }

    @Test
    void extendsContractsAcrossAllTeamsButNotFreeAgents() {
        AdminController controller = new AdminController();
        HumanRepository humanRepository = mock(HumanRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        ReflectionTestUtils.setField(controller, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(controller, "roundRepository", roundRepository);

        Human first = contractedPlayer(20L, 1L, 5, false);
        Human second = contractedPlayer(21L, 2L, 4, false);
        Human freeAgent = contractedPlayer(22L, null, 0, false);
        when(humanRepository.findAllByTypeId(1L)).thenReturn(List.of(first, second, freeAgent));
        Round round = new Round();
        round.setSeason(3);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));

        ResponseEntity<?> response = controller.extendPlayerContracts(
                Map.of("allTeams", true, "seasons", 10), adminRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(15, first.getContractEndSeason());
        assertEquals(14, second.getContractEndSeason());
        assertEquals(0, freeAgent.getContractEndSeason());
        verify(humanRepository).saveAll(List.of(first, second));
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Admin-Token")).thenReturn("admin-token-2026");
        return request;
    }

    private Human contractedPlayer(long id, Long teamId, int expiry, boolean retired) {
        Human player = new Human();
        player.setId(id);
        player.setTeamId(teamId);
        player.setTypeId(1L);
        player.setContractEndSeason(expiry);
        player.setRetired(retired);
        return player;
    }
}
