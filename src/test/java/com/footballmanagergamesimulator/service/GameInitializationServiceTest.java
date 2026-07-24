package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameInitializationServiceTest {

    private GameInitializationService service;

    @Mock private RoundRepository roundRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamFacilitiesRepository teamFacilitiesRepository;
    @Mock private HumanRepository humanRepository;
    @Mock private ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Mock private BootstrapService bootstrapService;
    @Mock private FixtureSchedulingService fixtureSchedulingService;
    @Mock private SeasonObjectiveService seasonObjectiveService;
    @Mock private SquadGenerationService squadGenerationService;
    @Mock private CompositeNameGenerator compositeNameGenerator;
    @Mock private TacticService tacticService;
    @Mock private StaffService staffService;
    @Mock private NewSeasonSetupProcessor newSeasonSetupProcessor;
    @Mock private PrebuiltDataService prebuiltDataService;
    @Mock private SuperCupService superCupService;
    @Mock private NewSeasonPlayerReadinessService newSeasonPlayerReadinessService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GameInitializationService();
        inject("roundRepository", roundRepository);
        inject("competitionRepository", competitionRepository);
        inject("competitionTeamInfoRepository", competitionTeamInfoRepository);
        inject("teamRepository", teamRepository);
        inject("teamFacilitiesRepository", teamFacilitiesRepository);
        inject("humanRepository", humanRepository);
        inject("scorerLeaderboardRepository", scorerLeaderboardRepository);
        inject("bootstrapService", bootstrapService);
        inject("fixtureSchedulingService", fixtureSchedulingService);
        inject("seasonObjectiveService", seasonObjectiveService);
        inject("squadGenerationService", squadGenerationService);
        inject("compositeNameGenerator", compositeNameGenerator);
        inject("tacticService", tacticService);
        inject("staffService", staffService);
        inject("newSeasonSetupProcessor", newSeasonSetupProcessor);
        inject("prebuiltDataService", prebuiltDataService);
        inject("superCupService", superCupService);
        inject("newSeasonPlayerReadinessService", newSeasonPlayerReadinessService);
    }

    @Test
    void coldStartSchedulesTheWorldBeforeAnyManagerOrChairmanOnboarding() {
        when(roundRepository.findById(1L)).thenReturn(Optional.empty());
        when(competitionRepository.findIdsByTypeId(1)).thenReturn(Set.of(11L));
        when(competitionRepository.findIdsByTypeId(3)).thenReturn(Set.of(12L));
        when(teamRepository.findAll()).thenReturn(List.of());
        when(humanRepository.findAllByTypeId(anyLong())).thenReturn(List.of());
        when(scorerLeaderboardRepository.findAll()).thenReturn(List.of());

        service.initializeRound();

        verify(bootstrapService).initialization();
        verify(fixtureSchedulingService).getFixturesForRound("11", "1");
        verify(fixtureSchedulingService).getFixturesForRound("12", "1");
        verify(fixtureSchedulingService).generateSeasonCalendar(1);
        verify(newSeasonSetupProcessor).regenerateAllCupBrackets(1);
    }

    private void inject(String field, Object value) {
        ReflectionTestUtils.setField(service, field, value);
    }
}
