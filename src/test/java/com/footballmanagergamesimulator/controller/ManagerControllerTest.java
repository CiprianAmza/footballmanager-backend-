package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.CompetitionStatLine;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.StatsAggregationService;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagerControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void completedCurrentSeasonIsNotAddedAgainAsALiveSnapshot() {
        ManagerController controller = new ManagerController();
        ManagerHistoryRepository historyRepository = mock(ManagerHistoryRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TeamCompetitionDetailRepository standingsRepository = mock(TeamCompetitionDetailRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        StatsAggregationService statsService = mock(StatsAggregationService.class);
        UserRepository userRepository = mock(UserRepository.class);

        ReflectionTestUtils.setField(controller, "managerHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(controller, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(controller, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(controller, "teamCompetitionDetailRepository", standingsRepository);
        ReflectionTestUtils.setField(controller, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(controller, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(controller, "statsAggregationService", statsService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        long managerId = 86L;
        long teamId = 86L;
        Human manager = new Human();
        manager.setId(managerId);
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setTeamId(teamId);
        manager.setName("Ciprian");
        Team team = new Team();
        team.setId(teamId);
        team.setName("Sherlock FC");
        ManagerHistory archivedSeason = new ManagerHistory();
        archivedSeason.setManagerId(managerId);
        archivedSeason.setTeamId(teamId);
        archivedSeason.setSeasonNumber(13);
        archivedSeason.setTeamName(team.getName());
        Round round = new Round();
        round.setSeason(13);
        CompetitionStatLine cup = new CompetitionStatLine(7L, 2, "Literature Cup", 13);
        cup.setTeamId(teamId);

        when(humanRepository.findById(managerId)).thenReturn(Optional.of(manager));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(historyRepository.findAllByManagerId(managerId)).thenReturn(List.of(archivedSeason));
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
        when(statsService.getTeamCompetitionBreakdown(teamId)).thenReturn(List.of(cup));

        Map<String, Object> profile = controller.getManagerProfile(managerId);
        List<CompetitionStatLine> breakdown = (List<CompetitionStatLine>) profile.get("competitionBreakdown");

        assertNull(profile.get("currentSeason"));
        assertEquals(1, profile.get("seasonsManaged"));
        assertEquals(1, breakdown.size());
        assertEquals(13, breakdown.get(0).getSeasonNumber());
    }
}
