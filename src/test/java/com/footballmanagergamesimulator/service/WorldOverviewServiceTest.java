package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldOverviewServiceTest {

    private TeamRepository teamRepository;
    private HumanRepository humanRepository;
    private PlayerSkillsRepository playerSkillsRepository;
    private CompetitionRepository competitionRepository;
    private PlayerValueService playerValueService;
    private GameStateService gameStateService;
    private WorldOverviewService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        humanRepository = mock(HumanRepository.class);
        playerSkillsRepository = mock(PlayerSkillsRepository.class);
        competitionRepository = mock(CompetitionRepository.class);
        playerValueService = mock(PlayerValueService.class);
        gameStateService = mock(GameStateService.class);
        when(playerValueService.familiarityFactor(anyString(), anyString())).thenReturn(1.0);
        when(gameStateService.currentSeason()).thenReturn(3);
        service = new WorldOverviewService(teamRepository, humanRepository, playerSkillsRepository,
                competitionRepository, playerValueService, new TacticService(), gameStateService);
    }

    @Test
    void ranksEveryClubAndBuildsAUniqueNaturalPositionWorldEleven() {
        Team stronger = team(1, "Stronger FC", 10, 1_000);
        Team weaker = team(2, "Weaker FC", 10, 500);
        when(teamRepository.findAll()).thenReturn(List.of(stronger, weaker));

        List<Human> players = new ArrayList<>();
        addSquad(players, stronger.getId(), 100, 1);
        addSquad(players, weaker.getId(), 80, 100);
        when(humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)).thenReturn(players);
        when(humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)).thenReturn(List.of(
                manager(200, stronger.getId()), manager(201, weaker.getId())));
        when(playerSkillsRepository.findAllByPlayerIdIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of());

        Competition competition = new Competition();
        competition.setId(10);
        competition.setNationId(7);
        when(competitionRepository.findAll()).thenReturn(List.of(competition));

        Map<String, Object> values = service.teamValues();
        @SuppressWarnings("unchecked")
        List<WorldOverviewService.TeamValueRow> rows =
                (List<WorldOverviewService.TeamValueRow>) values.get("teams");
        assertEquals(3, values.get("season"));
        assertEquals(2, rows.size());
        assertEquals(stronger.getId(), rows.get(0).teamId());
        assertEquals(1, rows.get(0).rank());
        assertTrue(rows.get(0).bestPossibleXiRating() > rows.get(1).bestPossibleXiRating());

        Map<String, Object> bestEleven = service.worldBestEleven();
        @SuppressWarnings("unchecked")
        List<WorldOverviewService.WorldBestPlayer> selected =
                (List<WorldOverviewService.WorldBestPlayer>) bestEleven.get("players");
        assertEquals(11, selected.size());
        assertEquals(11, selected.stream().map(WorldOverviewService.WorldBestPlayer::playerId).distinct().count());
        assertTrue(selected.stream().allMatch(player -> player.teamId() == stronger.getId()));
        assertTrue(selected.stream().allMatch(player -> player.nationId() == 7));
    }

    private static Team team(long id, String name, long competitionId, int reputation) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setCompetitionId(competitionId);
        team.setReputation(reputation);
        team.setColor1("#112233");
        team.setColor2("#ddeeff");
        return team;
    }

    private static Human manager(long id, long teamId) {
        Human manager = new Human();
        manager.setId(id);
        manager.setTeamId(teamId);
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setName("Manager " + id);
        manager.setTacticStyle("442");
        return manager;
    }

    private static void addSquad(List<Human> players, long teamId, double rating, long firstId) {
        String[] positions = {"GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST"};
        for (int index = 0; index < positions.length; index++) {
            Human player = new Human();
            player.setId(firstId + index);
            player.setTypeId(TypeNames.PLAYER_TYPE);
            player.setTeamId(teamId);
            player.setName("Player " + player.getId());
            player.setPosition(positions[index]);
            player.setRating(rating);
            player.setTransferValue(Math.round(rating * 1_000));
            players.add(player);
        }
    }
}
