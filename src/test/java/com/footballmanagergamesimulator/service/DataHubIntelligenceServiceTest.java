package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataHubIntelligenceServiceTest {

    @Test
    void derivesTransparentTeamMetricsFromTheTeamsSideOfAMatch() {
        MatchStatsRepository stats = mock(MatchStatsRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        Team home = team(10, "Home");
        Team away = team(20, "Away");
        Competition league = new Competition(); league.setId(3); league.setName("League");
        MatchStats match = match();
        when(teams.findAll()).thenReturn(List.of(home, away));
        when(competitions.findAll()).thenReturn(List.of(league));
        when(fixtures.findAllBySeasonNumberAndTeamId("2", 10)).thenReturn(List.of());
        when(stats.findAllByTeam1IdAndSeasonNumber(10, 2)).thenReturn(List.of(match));
        when(stats.findAllByTeam2IdAndSeasonNumber(10, 2)).thenReturn(List.of());

        DataHubIntelligenceService.DataHubIntelligence result =
                new DataHubIntelligenceService(stats, fixtures, teams, competitions).intelligence(10, 2);

        assertEquals(1, result.seasonMetrics().matches());
        assertEquals(1.8, result.seasonMetrics().xgForPerMatch());
        assertEquals(.9, result.seasonMetrics().xgAgainstPerMatch());
        assertEquals(.12, result.seasonMetrics().xgPerShot());
        assertEquals(10.0, result.seasonMetrics().pressureProxy());
        assertEquals("Away", result.matches().get(0).opponentName());
        assertTrue(result.methodologyNote().contains("derived proxies"));
    }

    private Team team(long id, String name) { Team team = new Team(); team.setId(id); team.setName(name); return team; }

    private MatchStats match() {
        MatchStats row = new MatchStats();
        row.setId(1); row.setCompetitionId(3); row.setSeasonNumber(2); row.setRoundNumber(4);
        row.setTeam1Id(10); row.setTeam2Id(20); row.setHomeGoals(2); row.setAwayGoals(1);
        row.setHomeXg(180); row.setAwayXg(90); row.setHomePossession(56); row.setAwayPossession(44);
        row.setHomeShots(15); row.setAwayShots(8); row.setHomeShotsOnTarget(6);
        row.setHomePassAccuracy(85); row.setAwayPasses(420);
        row.setHomeTackles(20); row.setHomeInterceptions(12); row.setHomeFouls(10);
        return row;
    }
}
