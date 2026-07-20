package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EuropeanFixturePreparationServiceTest {

    private final CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
    private final CompetitionTeamInfoRepository teamInfoRepository = mock(CompetitionTeamInfoRepository.class);
    private final CompetitionTeamInfoMatchRepository matchRepository = mock(CompetitionTeamInfoMatchRepository.class);
    private final CalendarEventRepository calendarEventRepository = mock(CalendarEventRepository.class);
    private final EuropeanCompetitionService europeanCompetitionService = mock(EuropeanCompetitionService.class);
    private final FixtureSchedulingService fixtureSchedulingService = mock(FixtureSchedulingService.class);

    private EuropeanFixturePreparationService service;

    @BeforeEach
    void setUp() {
        service = new EuropeanFixturePreparationService();
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoRepository", teamInfoRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoMatchRepository", matchRepository);
        ReflectionTestUtils.setField(service, "calendarEventRepository", calendarEventRepository);
        ReflectionTestUtils.setField(service, "competitionFormatConfig", new CompetitionFormatConfig());
        ReflectionTestUtils.setField(service, "europeanCompetitionService", europeanCompetitionService);
        ReflectionTestUtils.setField(service, "fixtureSchedulingService", fixtureSchedulingService);
    }

    @Test
    void preparesLocPreliminaryBeforeItsSimulationDay() {
        long competitionId = 40L;
        Competition loc = competition(competitionId, 4);
        when(competitionRepository.findByIdForFixturePreparation(competitionId)).thenReturn(Optional.of(loc));
        when(teamInfoRepository.findAllByRoundAndCompetitionIdAndSeasonNumber(0, competitionId, 2))
                .thenReturn(participants(competitionId, 0, 2, 40));

        CompetitionTeamInfoMatch generated = new CompetitionTeamInfoMatch();
        when(matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                competitionId, 0, "2"))
                .thenReturn(List.of(), List.of(generated));

        assertTrue(service.prepareMatchday(competitionId, 1, 2));

        verify(europeanCompetitionService)
                .drawEuropeanPreliminarySeeded(competitionId, 0, 16);
        verify(fixtureSchedulingService)
                .assignMatchDayForNewRound(competitionId, 1, 2);
    }

    @Test
    void starsCupPlayoffWaitsForLocThirdPlacedTeams() {
        long competitionId = 50L;
        Competition starsCup = competition(competitionId, 5);
        when(competitionRepository.findByIdForFixturePreparation(competitionId)).thenReturn(Optional.of(starsCup));
        when(matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                competitionId, 7, "2")).thenReturn(List.of());
        when(teamInfoRepository.findAllByRoundAndCompetitionIdAndSeasonNumber(7, competitionId, 2))
                .thenReturn(participants(competitionId, 7, 2, 4));

        assertFalse(service.prepareMatchday(competitionId, 7, 2));

        verify(fixtureSchedulingService, never())
                .getFixturesForRound(String.valueOf(competitionId), "7", 2);
    }

    private Competition competition(long id, long typeId) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setTypeId(typeId);
        return competition;
    }

    private List<CompetitionTeamInfo> participants(long competitionId, long round,
                                                   long season, int count) {
        List<CompetitionTeamInfo> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            CompetitionTeamInfo entry = new CompetitionTeamInfo();
            entry.setCompetitionId(competitionId);
            entry.setRound(round);
            entry.setSeasonNumber(season);
            entry.setTeamId(i);
            entries.add(entry);
        }
        return entries;
    }
}
