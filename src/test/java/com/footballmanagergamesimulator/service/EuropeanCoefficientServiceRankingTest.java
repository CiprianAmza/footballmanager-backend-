package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EuropeanCoefficientServiceRankingTest {
    @Mock private ClubCoefficientRepository coefficients;
    @Mock private CompetitionTeamInfoRepository entries;
    @Mock private CompetitionRepository competitions;
    @Mock private TeamRepository teams;
    @Mock private RoundRepository rounds;
    @InjectMocks private EuropeanCoefficientService service;

    @Test
    void rankingDeduplicatesEuropeanEntriesAndReadsRollingWindowOnce() {
        Round round = new Round();
        round.setSeason(13);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(competitions.findAll()).thenReturn(List.of(
                competition(1, 1, 1), competition(2, 1, 2), competition(3, 1, 3),
                competition(4, 4, 0)));
        when(teams.findAll()).thenReturn(List.of(team(10, 1, 100), team(20, 2, 100), team(30, 3, 200)));
        when(entries.findAllBySeasonNumberBetween(9, 13)).thenReturn(List.of(
                entry(10, 4, 12), entry(10, 4, 12), entry(20, 4, 13)));
        when(coefficients.findAllBySeasonNumberBetween(9, 13)).thenReturn(List.of(
                coefficient(10, 12, 4), coefficient(20, 13, 6)));

        assertEquals(List.of(2L, 1L, 3L), service.getLeagueIdsSortedByCoefficient());
        assertEquals(List.of(3L, 2L, 1L), service.rankFirstLeaguesByCoefficient());
        verify(entries, times(2)).findAllBySeasonNumberBetween(9, 13);
        verify(coefficients, times(2)).findAllBySeasonNumberBetween(9, 13);
        verify(teams, never()).findById(anyLong());
        verify(competitions, never()).findById(anyLong());
    }

    private static Competition competition(long id, int type, long nation) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setTypeId(type);
        competition.setTier(1);
        competition.setNationId(nation);
        return competition;
    }

    private static Team team(long id, long competitionId, int reputation) {
        Team team = new Team();
        team.setId(id);
        team.setCompetitionId(competitionId);
        team.setReputation(reputation);
        return team;
    }

    private static CompetitionTeamInfo entry(long teamId, long competitionId, long season) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setTeamId(teamId);
        entry.setCompetitionId(competitionId);
        entry.setSeasonNumber(season);
        return entry;
    }

    private static ClubCoefficient coefficient(long teamId, int season, double points) {
        ClubCoefficient coefficient = new ClubCoefficient();
        coefficient.setTeamId(teamId);
        coefficient.setSeasonNumber(season);
        coefficient.setPoints(points);
        return coefficient;
    }
}
