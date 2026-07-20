package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagerCareerServiceTest {

    @InjectMocks
    private ManagerCareerService service;

    @Mock private TeamRepository teamRepository;
    @Mock private HumanRepository humanRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private CompetitionHistoryRepository competitionHistoryRepository;
    @Mock private ManagerHistoryRepository managerHistoryRepository;
    @Mock private SeasonObjectiveRepository seasonObjectiveRepository;
    @Mock private UserContext userContext;
    @Mock private UserRepository userRepository;
    @Mock private GameCalendarRepository gameCalendarRepository;
    @Mock private ManagerInboxRepository managerInboxRepository;
    @Mock private CompositeNameGenerator compositeNameGenerator;
    @Mock private TacticService tacticService;
    @Mock private TeamCompetitionDetailRepository detailRepository;
    @Mock private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Mock private JobOfferService jobOfferService;
    @Mock private HumanService humanService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void aClubCannotSackMultipleAiManagersInTheSameSeason() {
        int season = 2;
        Competition league = new Competition();
        league.setId(1L);
        league.setTypeId(1L);

        Team strugglingFavourite = team(1L, 10_000);
        List<Team> teams = new ArrayList<>();
        teams.add(strugglingFavourite);
        for (long id = 2; id <= 5; id++) {
            teams.add(team(id, 1_000));
        }

        Human manager = new Human();
        manager.setId(99L);
        manager.setName("First manager");
        manager.setTeamId(1L);
        manager.setTypeId(TypeNames.MANAGER_TYPE);

        List<TeamCompetitionDetail> table = new ArrayList<>();
        for (int index = 0; index < teams.size(); index++) {
            Team team = teams.get(index);
            TeamCompetitionDetail row = new TeamCompetitionDetail();
            row.setCompetitionId(1L);
            row.setTeamId(team.getId());
            row.setGames(10);
            row.setPoints(team.getId() == 1L ? 0 : 30 - index);
            table.add(row);
        }

        List<CompetitionTeamInfo> memberships = teams.stream().map(team -> {
            CompetitionTeamInfo info = new CompetitionTeamInfo();
            info.setCompetitionId(1L);
            info.setSeasonNumber(season);
            info.setTeamId(team.getId());
            return info;
        }).toList();

        when(detailRepository.findAllByCompetitionIdIn(java.util.Set.of(1L))).thenReturn(table);
        when(teamRepository.findAllById(any())).thenReturn(teams);
        when(humanRepository.findAllByTeamIdInAndTypeId(any(), anyLong())).thenReturn(List.of(manager));
        when(competitionTeamInfoRepository.findAllByCompetitionIdInAndSeasonNumber(
                java.util.Set.of(1L), season)).thenReturn(memberships);
        when(userContext.isHumanTeam(anyLong())).thenReturn(false);
        when(userRepository.findAll()).thenReturn(List.of());

        service.evaluateMidSeasonSackings(season, java.util.Set.of(1L));
        service.evaluateMidSeasonSackings(season, java.util.Set.of(1L));

        assertEquals(season, strugglingFavourite.getLastMidSeasonManagerChangeSeason());
        verify(humanRepository, times(1)).save(manager);
        verify(teamRepository, times(1)).save(strugglingFavourite);
        verify(humanService, times(1)).ensureTeamHasManager(strugglingFavourite.getId());
        verify(competitionTeamInfoRepository, never()).findAll();
    }

    @Test
    void alwaysContinueProtectsHumanManagerFromDismissalAndBlockingInbox() {
        int season = 7;
        long teamId = 12L;

        Human manager = new Human();
        manager.setId(77L);
        manager.setTeamId(teamId);
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setAlwaysContinue(true);

        User user = new User();
        user.setId(4);
        user.setTeamId(teamId);
        user.setManagerId(manager.getId());

        SeasonObjective failedCriticalObjective = new SeasonObjective();
        failedCriticalObjective.setStatus("failed");
        failedCriticalObjective.setImportance("critical");
        failedCriticalObjective.setObjectiveType("league_position");
        failedCriticalObjective.setTargetValue(1);
        failedCriticalObjective.setActualValue(10);

        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(teamId));
        when(userRepository.findAllByTeamId(teamId)).thenReturn(List.of(user));
        when(humanRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(teamId, season))
                .thenReturn(List.of(failedCriticalObjective));
        when(teamRepository.findAll()).thenReturn(List.of());
        when(humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)).thenReturn(List.of());

        service.checkManagerFiring(season);

        verify(userRepository, never()).save(any(User.class));
        verify(managerInboxRepository, never()).save(any());
        assertEquals(teamId, user.getTeamId());
        assertEquals(teamId, manager.getTeamId());
    }

    private Team team(long id, int reputation) {
        Team team = new Team();
        team.setId(id);
        team.setName("Team " + id);
        team.setReputation(reputation);
        return team;
    }
}
