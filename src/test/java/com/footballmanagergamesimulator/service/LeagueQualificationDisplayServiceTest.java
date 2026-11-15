package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.EuropeanQualificationPolicy;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeagueQualificationDisplayServiceTest {

    private final CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
    private final CompetitionHistoryRepository competitionHistoryRepository = mock(CompetitionHistoryRepository.class);
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository = mock(CompetitionTeamInfoRepository.class);
    private final CompetitionTeamInfoDetailRepository detailRepository = mock(CompetitionTeamInfoDetailRepository.class);
    private final TeamCompetitionDetailRepository standingsRepository = mock(TeamCompetitionDetailRepository.class);
    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final RoundRepository roundRepository = mock(RoundRepository.class);
    private final EuropeanCoefficientService coefficientService = mock(EuropeanCoefficientService.class);
    private final EuropeanQualificationPolicy qualificationPolicy = mock(EuropeanQualificationPolicy.class);

    private final LeagueQualificationDisplayService service = new LeagueQualificationDisplayService(
            competitionRepository, competitionHistoryRepository, competitionTeamInfoRepository,
            detailRepository, standingsRepository, teamRepository, roundRepository,
            coefficientService, qualificationPolicy);

    @BeforeEach
    void currentSeasonIsFive() {
        Round round = new Round();
        round.setSeason(5);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
    }

    @Test
    void hidesRelegationWhenNationHasNoImmediatelyLowerLeague() {
        Competition topFlight = competition(1, 7, Competition.LEAGUE, 1, "Only League");
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(topFlight));
        when(competitionRepository.findAll()).thenReturn(List.of(topFlight));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(1, 4))
                .thenReturn(standings(1, 4, 6));
        when(coefficientService.getLeagueIdsSortedByCoefficient()).thenReturn(List.of(1L));
        when(qualificationPolicy.totalForRank(1)).thenReturn(2);

        var result = service.context(1, 4);

        assertFalse(result.hasLowerTier());
        assertNull(result.relegationFrom());
        assertEquals("LOC", qualification(result, 1).route());
        assertEquals("STARS_CUP", qualification(result, 3).route());
    }

    @Test
    void marksCupWinnerForStarsCupAndUsesDynamicRelegationPositions() {
        Competition topFlight = competition(1, 7, Competition.LEAGUE, 1, "First League");
        Competition lowerTier = competition(2, 7, Competition.LEAGUE, 2, "Second League");
        Competition cup = competition(3, 7, Competition.CUP, 1, "National Cup");
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(topFlight));
        when(competitionRepository.findAll()).thenReturn(List.of(topFlight, lowerTier, cup));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(1, 4))
                .thenReturn(standings(1, 4, 8));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(3, 4))
                .thenReturn(List.of(history(3, 4, 8, 1)));
        when(coefficientService.getLeagueIdsSortedByCoefficient()).thenReturn(List.of(1L));
        when(qualificationPolicy.totalForRank(1)).thenReturn(4);
        Team winner = new Team();
        winner.setId(8);
        winner.setName("Cup Heroes");
        when(teamRepository.findById(8L)).thenReturn(Optional.of(winner));

        var result = service.context(1, 4);

        assertTrue(result.hasLowerTier());
        assertEquals(7, result.relegationFrom());
        assertEquals(8, result.cupWinnerTeamId());
        assertEquals("Cup Heroes", result.cupWinnerTeamName());
        assertEquals("STARS_CUP", result.cupWinnerRoute());
        assertEquals("CUP_WINNER", qualification(result, 8).source());
    }

    @Test
    void keepsLocPriorityAndReallocatesCupPlaceToNextEligibleLeagueTeam() {
        Competition topFlight = competition(1, 7, Competition.LEAGUE, 1, "First League");
        Competition cup = competition(3, 7, Competition.CUP, 1, "National Cup");
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(topFlight));
        when(competitionRepository.findAll()).thenReturn(List.of(topFlight, cup));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(1, 4))
                .thenReturn(standings(1, 4, 8));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(3, 4))
                .thenReturn(List.of(history(3, 4, 2, 1)));
        when(coefficientService.getLeagueIdsSortedByCoefficient()).thenReturn(List.of(1L));
        when(qualificationPolicy.totalForRank(1)).thenReturn(4);
        Team winner = new Team();
        winner.setId(2);
        winner.setName("Double Winners");
        when(teamRepository.findById(2L)).thenReturn(Optional.of(winner));

        var result = service.context(1, 4);

        assertEquals("LOC", result.cupWinnerRoute());
        assertEquals("LOC", qualification(result, 2).route());
        assertEquals("LEAGUE_POSITION", qualification(result, 5).source());
        assertEquals("CUP_REALLOCATION", qualification(result, 6).source());
    }

    @Test
    void pendingNationKeepsCupWinnerAndDomesticRelegationButHasNoEuropeanPlaces() {
        Competition league = competition(22, 11, Competition.LEAGUE, 1, "Liga Veyra");
        Competition lower = competition(90, 11, Competition.LEAGUE, 2, "Veyra II");
        Competition cup = competition(23, 11, Competition.CUP, 1, "Cupa Veyra");
        when(competitionRepository.findById(22L)).thenReturn(Optional.of(league));
        when(competitionRepository.findAll()).thenReturn(List.of(league, lower, cup));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(22, 4))
                .thenReturn(standings(22, 4, 12));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(23, 4))
                .thenReturn(List.of(history(23, 4, 1, 1)));
        when(coefficientService.getLeagueIdsSortedByCoefficient())
                .thenReturn(List.of(1L, 3L, 6L, 8L, 12L, 14L, 16L, 18L, 20L));
        Team winner = new Team(); winner.setId(1); winner.setName("Avelis");
        when(teamRepository.findById(1L)).thenReturn(Optional.of(winner));

        var result = service.context(22, 4);

        assertTrue(result.hasLowerTier());
        assertEquals(11, result.relegationFrom());
        assertEquals(0, result.locSpots());
        assertEquals(0, result.starsCupSpots());
        assertEquals("Avelis", result.cupWinnerTeamName());
        assertEquals(1L, result.cupWinnerTeamId());
        assertNull(result.cupWinnerRoute());
        assertTrue(result.qualifications().isEmpty());
    }

    @Test
    void keepsExistingPositiveRankEightCupWinnerBehaviorUnchanged() {
        Competition league = competition(18, 8, Competition.LEAGUE, 1, "Mareval League");
        Competition cup = competition(19, 8, Competition.CUP, 1, "Mareval Cup");
        when(competitionRepository.findById(18L)).thenReturn(Optional.of(league));
        when(competitionRepository.findAll()).thenReturn(List.of(league, cup));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(18, 4))
                .thenReturn(standings(18, 4, 12));
        when(competitionHistoryRepository.findAllByCompetitionIdAndSeasonNumber(19, 4))
                .thenReturn(List.of(history(19, 4, 2, 1)));
        when(coefficientService.getLeagueIdsSortedByCoefficient())
                .thenReturn(List.of(1L, 3L, 6L, 8L, 12L, 14L, 16L, 18L, 20L));

        var result = service.context(18, 4);

        assertEquals("STARS_CUP", result.cupWinnerRoute());
        assertEquals("CUP_WINNER", qualification(result, 2).source());
    }

    private static LeagueQualificationDisplayService.TeamQualification qualification(
            LeagueQualificationDisplayService.LeagueQualificationContext context, long teamId) {
        return context.qualifications().stream()
                .filter(entry -> entry.teamId() == teamId)
                .findFirst()
                .orElseThrow();
    }

    private static Competition competition(long id, long nationId, long typeId, int tier, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setNationId(nationId);
        competition.setTypeId(typeId);
        competition.setTier(tier);
        competition.setName(name);
        return competition;
    }

    private static List<CompetitionHistory> standings(long competitionId, long season, int teams) {
        List<CompetitionHistory> rows = new ArrayList<>();
        for (int position = 1; position <= teams; position++) {
            rows.add(history(competitionId, season, position, position));
        }
        return rows;
    }

    private static CompetitionHistory history(
            long competitionId, long season, long teamId, long position) {
        CompetitionHistory history = new CompetitionHistory();
        history.setId(competitionId * 1000 + teamId);
        history.setCompetitionId(competitionId);
        history.setSeasonNumber(season);
        history.setTeamId(teamId);
        history.setLastPosition(position);
        return history;
    }
}
