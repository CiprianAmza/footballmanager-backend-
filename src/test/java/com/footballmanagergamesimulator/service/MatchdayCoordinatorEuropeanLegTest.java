package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchdayCoordinatorEuropeanLegTest {

    private MatchdayCoordinator coordinator;
    private MatchRoundSimulator roundSimulator;
    private FixtureSchedulingService fixtureSchedulingService;
    private EuropeanCompetitionService europeanCompetitionService;

    @BeforeEach
    void setUp() {
        coordinator = new MatchdayCoordinator();
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoDetailRepository detailRepository =
                mock(CompetitionTeamInfoDetailRepository.class);
        roundSimulator = mock(MatchRoundSimulator.class);
        fixtureSchedulingService = mock(FixtureSchedulingService.class);
        europeanCompetitionService = mock(EuropeanCompetitionService.class);

        Competition loc = new Competition();
        loc.setId(40L);
        loc.setTypeId(4);
        when(competitionRepository.findById(40L)).thenReturn(Optional.of(loc));
        when(detailRepository.findAllByCompetitionIdAndRoundIdAndSeasonNumber(40L, 0, 2L))
                .thenReturn(List.of());

        ReflectionTestUtils.setField(coordinator, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(coordinator, "competitionTeamInfoDetailRepository", detailRepository);
        ReflectionTestUtils.setField(coordinator, "matchRoundSimulator", roundSimulator);
        ReflectionTestUtils.setField(coordinator, "fixtureSchedulingService", fixtureSchedulingService);
        ReflectionTestUtils.setField(coordinator, "europeanCompetitionService", europeanCompetitionService);
        ReflectionTestUtils.setField(coordinator, "competitionFormat", new CompetitionFormatConfig());
        ReflectionTestUtils.setField(coordinator, "europeanFixturePreparationService",
                mock(EuropeanFixturePreparationService.class));
    }

    @Test
    void locPreliminaryWaitsForSecondLegBeforeDroppingTheLoser() {
        coordinator.simulateMatchday(40L, 1, 2, 1);

        verify(fixtureSchedulingService).getFixturesForRound("40", "0");
        verify(roundSimulator).simulateRound("40", "0", 1);
        verify(europeanCompetitionService, never()).assignLocLosersToStarsCup(40L, 0);

        coordinator.simulateMatchday(40L, 1, 2, 2);

        verify(roundSimulator).simulateRound("40", "0", 2);
        verify(europeanCompetitionService).assignLocLosersToStarsCup(40L, 0);
    }
}
