package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetitionRecordServiceTest {

    @Mock private ScorerRepository scorerRepository;
    @Mock private HumanRepository humanRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private GameStateService gameStateService;

    private CompetitionRecordService service;

    @BeforeEach
    void setUp() {
        service = new CompetitionRecordService(
                scorerRepository, humanRepository, competitionRepository, gameStateService);
    }

    @Test
    void mapsNamesSharedRanksAndMultipleClubCareers() {
        var first = seasonRow(10L, 5, 22L, 3L, 12L, 4L, 1L, "Sherlock FC");
        var tied = seasonRow(11L, 4, 19L, 3L, 12L, 1L, 1L, "Xenon");
        var career = allTimeRow(10L, 3, 7, 86L, 48L, 31L, 2L, 7L, "Sherlock FC");

        when(scorerRepository.findCompetitionSeasonGoalRecords(eq(4L), any(Pageable.class)))
                .thenReturn(List.of(first, tied));
        when(scorerRepository.findCompetitionSeasonAssistRecords(eq(4L), any(Pageable.class)))
                .thenReturn(List.of(tied));
        when(scorerRepository.findCompetitionAllTimeGoalRecords(eq(4L), any(Pageable.class)))
                .thenReturn(List.of(career));
        when(scorerRepository.findCompetitionAllTimeAssistRecords(eq(4L), any(Pageable.class)))
                .thenReturn(List.of(career));

        Human player10 = player(10L, "Saviola");
        Human player11 = player(11L, "Dostoievski");
        when(humanRepository.findAllById(any())).thenReturn(List.of(player10, player11));

        Competition competition = new Competition();
        competition.setId(4L);
        competition.setName("League of Champions");
        when(competitionRepository.findById(4L)).thenReturn(Optional.of(competition));
        when(gameStateService.currentSeason()).thenReturn(8);

        CompetitionRecordService.CompetitionRecords result = service.records(4L, 200);

        assertEquals("League of Champions", result.competitionName());
        assertEquals(8, result.currentSeason());
        assertEquals(100, result.limit());
        assertEquals(1, result.goalsSingleSeason().get(0).rank());
        assertEquals(1, result.goalsSingleSeason().get(1).rank());
        assertEquals("Saviola", result.goalsSingleSeason().get(0).playerName());
        assertEquals(12, result.goalsSingleSeason().get(0).recordValue());

        CompetitionRecordService.RecordRow careerRow = result.goalsAllTime().get(0);
        assertEquals(31, careerRow.recordValue());
        assertEquals(3, careerRow.firstSeason());
        assertEquals(7, careerRow.lastSeason());
        assertTrue(careerRow.multipleClubs());
        assertNull(careerRow.teamId());
        assertEquals("Multiple clubs", careerRow.teamName());
    }

    private Human player(long id, String name) {
        Human human = new Human();
        human.setId(id);
        human.setName(name);
        return human;
    }

    private ScorerRepository.CompetitionSeasonRecordAggregate seasonRow(
            long playerId, int season, long appearances, long assists, long goals,
            long teamId, long teamCount, String teamName) {
        var row = mock(ScorerRepository.CompetitionSeasonRecordAggregate.class);
        when(row.getPlayerId()).thenReturn(playerId);
        when(row.getSeasonNumber()).thenReturn(season);
        when(row.getAppearances()).thenReturn(appearances);
        when(row.getAssists()).thenReturn(assists);
        when(row.getGoals()).thenReturn(goals);
        when(row.getTeamId()).thenReturn(teamId);
        when(row.getTeamCount()).thenReturn(teamCount);
        when(row.getTeamName()).thenReturn(teamName);
        return row;
    }

    private ScorerRepository.CompetitionAllTimeRecordAggregate allTimeRow(
            long playerId, int firstSeason, int lastSeason, long appearances,
            long assists, long goals, long teamCount, long teamId, String teamName) {
        var row = mock(ScorerRepository.CompetitionAllTimeRecordAggregate.class);
        when(row.getPlayerId()).thenReturn(playerId);
        when(row.getFirstSeason()).thenReturn(firstSeason);
        when(row.getLastSeason()).thenReturn(lastSeason);
        when(row.getAppearances()).thenReturn(appearances);
        when(row.getAssists()).thenReturn(assists);
        when(row.getGoals()).thenReturn(goals);
        when(row.getTeamId()).thenReturn(teamId);
        when(row.getTeamCount()).thenReturn(teamCount);
        when(row.getTeamName()).thenReturn(teamName);
        return row;
    }
}
