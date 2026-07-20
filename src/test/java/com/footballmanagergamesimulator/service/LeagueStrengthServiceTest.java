package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeagueStrengthServiceTest {

    @Test
    void ranksChampionshipsByAverageClubTopElevenAndAssignsConfiguredMultiplier() {
        LeagueStrengthService service = new LeagueStrengthService();
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository membershipRepository = mock(CompetitionTeamInfoRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoRepository", membershipRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "weightingConfig", new AwardWeightingConfig());

        List<Competition> leagues = List.of(
                league(1, "Alpha League"), league(2, "Beta League"),
                league(3, "Gamma League"), league(4, "Delta League"));
        when(competitionRepository.findAll()).thenReturn(leagues);

        List<CompetitionTeamInfo> memberships = new ArrayList<>();
        List<Team> teams = new ArrayList<>();
        List<Human> players = new ArrayList<>();
        double[] ratings = {210, 180, 160, 195};
        for (int leagueIndex = 0; leagueIndex < leagues.size(); leagueIndex++) {
            long teamId = 100 + leagueIndex;
            memberships.add(membership(leagueIndex + 1L, teamId));
            teams.add(team(teamId, "Team " + teamId));
            for (int player = 0; player < 12; player++) {
                players.add(player(teamId * 100 + player, teamId,
                        ratings[leagueIndex] - Math.min(player, 10)));
            }
        }
        when(membershipRepository.findAllBySeasonNumber(2)).thenReturn(memberships);
        when(teamRepository.findAllById(any())).thenReturn(teams);
        when(humanRepository.findAllByTeamIdInAndTypeId(any(), anyLong())).thenReturn(players);

        LeagueStrengthService.LeagueStrengthTable table = service.calculate(2);

        assertEquals(List.of("Alpha League", "Delta League", "Beta League", "Gamma League"),
                table.ranking().stream().map(LeagueStrengthService.LeagueStrengthEntry::competitionName).toList());
        assertEquals(4.0, table.ranking().get(0).multiplier());
        assertEquals(3.0, table.ranking().get(3).multiplier());
        assertEquals(4.0, table.teamMultiplier(100L));
        assertEquals(205.0, table.ranking().get(0).averageTopElevenRating());
        assertEquals(11, table.ranking().get(0).teams().get(0).ratedPlayerCount());
    }

    private Competition league(long id, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setName(name);
        competition.setTypeId(1);
        return competition;
    }

    private CompetitionTeamInfo membership(long competitionId, long teamId) {
        CompetitionTeamInfo info = new CompetitionTeamInfo();
        info.setCompetitionId(competitionId);
        info.setTeamId(teamId);
        info.setSeasonNumber(2);
        return info;
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private Human player(long id, long teamId, double rating) {
        Human player = new Human();
        player.setId(id);
        player.setTeamId(teamId);
        player.setRating(rating);
        return player;
    }
}
