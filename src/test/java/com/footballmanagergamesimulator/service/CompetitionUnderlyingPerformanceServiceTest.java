package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionUnderlyingPerformanceServiceTest {

    private final MatchStatsRepository matchStatsRepository = mock(MatchStatsRepository.class);
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository = mock(CompetitionTeamInfoRepository.class);
    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final CompetitionUnderlyingPerformanceService service = new CompetitionUnderlyingPerformanceService(
            matchStatsRepository, competitionTeamInfoRepository, teamRepository);

    @Test
    void buildsRollingControlBalancedAndOpponentTierMetrics() {
        List<MatchStats> matches = List.of(
                match(1, 1, 2, 2, 0, 180, 70),
                match(2, 3, 4, 1, 0, 120, 80),
                match(3, 1, 3, 1, 1, 110, 100),
                match(4, 2, 4, 0, 1, 60, 130),
                match(5, 4, 1, 2, 1, 80, 160),
                match(6, 2, 3, 0, 0, 90, 90));
        when(matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(50, 2)).thenReturn(matches);
        when(competitionTeamInfoRepository.findAllByCompetitionIdAndSeasonNumber(50, 2))
                .thenReturn(List.of(info(1), info(2), info(3), info(4)));
        when(teamRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(team(1, "Alpha"), team(2, "Beta"), team(3, "Gamma"), team(4, "Delta")));

        CompetitionUnderlyingPerformanceService.CompetitionUnderlyingPerformance result = service.performance(50, 2);

        assertThat(result.matches()).isEqualTo(6);
        assertThat(result.teams()).isEqualTo(4);
        assertThat(result.balancedXgThreshold()).isEqualTo(0.25);
        assertThat(result.teamPerformance()).extracting("rank").containsExactly(1, 2, 3, 4);

        CompetitionUnderlyingPerformanceService.TeamUnderlyingRow alpha = result.teamPerformance().stream()
                .filter(row -> row.teamId() == 1).findFirst().orElseThrow();
        assertThat(alpha.matches()).isEqualTo(3);
        assertThat(alpha.xgDifferencePer90()).isEqualTo(0.67);
        assertThat(alpha.rolling5().sampleMatches()).isEqualTo(3);
        assertThat(alpha.rolling10().sampleMatches()).isEqualTo(3);
        assertThat(alpha.higherXgMatchPercentage()).isEqualTo(100.0);
        assertThat(alpha.balancedMatches().matches()).isEqualTo(1);
        assertThat(alpha.balancedMatches().draws()).isEqualTo(1);
        assertThat(alpha.versusTop4().matches()).isEqualTo(3);
        assertThat(alpha.luckIndex()).isBetween(-3.0, 3.0);
    }

    @Test
    void includesRegisteredTeamsBeforeTheirFirstMatch() {
        when(matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(50, 1)).thenReturn(List.of());
        when(competitionTeamInfoRepository.findAllByCompetitionIdAndSeasonNumber(50, 1)).thenReturn(List.of(info(7)));
        when(teamRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(team(7, "Seven")));

        CompetitionUnderlyingPerformanceService.CompetitionUnderlyingPerformance result = service.performance(50, 1);

        assertThat(result.teamPerformance()).hasSize(1);
        assertThat(result.teamPerformance().get(0).matches()).isZero();
        assertThat(result.teamPerformance().get(0).rolling20().sampleMatches()).isZero();
    }

    private MatchStats match(long id, long home, long away, int homeGoals, int awayGoals, int homeXg, int awayXg) {
        MatchStats match = new MatchStats();
        match.setId(id);
        match.setRoundNumber((int) id);
        match.setTeam1Id(home);
        match.setTeam2Id(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setHomeXg(homeXg);
        match.setAwayXg(awayXg);
        return match;
    }

    private CompetitionTeamInfo info(long teamId) {
        CompetitionTeamInfo info = new CompetitionTeamInfo();
        info.setTeamId(teamId);
        return info;
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }
}
