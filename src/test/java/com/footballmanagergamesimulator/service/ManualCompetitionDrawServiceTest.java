package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualCompetitionDrawServiceTest {

    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
    private final CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
    private final CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CalendarEventRepository calendarEvents = mock(CalendarEventRepository.class);
    private final PredeterminedScoreRepository predeterminedScores = mock(PredeterminedScoreRepository.class);
    private final RoundRepository rounds = mock(RoundRepository.class);
    private final CompetitionProgressService progress = mock(CompetitionProgressService.class);
    private final EuropeanDrawService europeanDraws = mock(EuropeanDrawService.class);
    private final EuropeanCompetitionService europeanCompetitions = mock(EuropeanCompetitionService.class);
    private final EuropeanCoefficientService coefficients = mock(EuropeanCoefficientService.class);
    private final FixtureSchedulingService scheduling = mock(FixtureSchedulingService.class);
    private ManualCompetitionDrawService service;

    @BeforeEach
    void setUp() {
        service = new ManualCompetitionDrawService(competitions, entries, fixtures, results, teams,
                calendarEvents, predeterminedScores, rounds, new CompetitionFormatConfig(), progress,
                europeanDraws, europeanCompetitions, coefficients, scheduling);
        Round current = new Round();
        current.setSeason(2);
        when(rounds.findById(1L)).thenReturn(Optional.of(current));
        when(predeterminedScores.findAllByConsumedFalse()).thenReturn(List.of());
    }

    @Test
    void redrawsTheExistingDomesticBracketSlotsWithoutReplacingThem() {
        Competition cup = competition(20L, 2, "National Cup");
        when(competitions.findById(20L)).thenReturn(Optional.of(cup));
        when(results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(20L, 1L, 2))
                .thenReturn(List.of());
        CompetitionTeamInfoMatch first = fixture(20L, 1, 1, 1, 2);
        CompetitionTeamInfoMatch second = fixture(20L, 1, 2, 3, 4);
        when(fixtures.findAllByCompetitionIdAndRoundAndSeasonNumber(20L, 1L, "2"))
                .thenReturn(List.of(first, second));

        service.complete(new ManualCompetitionDrawService.DrawCommand(20L, 2, 1,
                List.of(new ManualCompetitionDrawService.Pairing(1, 3),
                        new ManualCompetitionDrawService.Pairing(2, 4)),
                null, List.of()));

        assertEquals(1, first.getTeam1Id());
        assertEquals(3, first.getTeam2Id());
        assertEquals(2, second.getTeam1Id());
        assertEquals(4, second.getTeam2Id());
        verify(fixtures).saveAll(List.of(first, second));
    }

    @Test
    void rejectsARepeatedTeamBeforeChangingACupBracket() {
        Competition cup = competition(20L, 2, "National Cup");
        when(competitions.findById(20L)).thenReturn(Optional.of(cup));
        when(results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(20L, 1L, 2))
                .thenReturn(List.of());
        when(fixtures.findAllByCompetitionIdAndRoundAndSeasonNumber(20L, 1L, "2"))
                .thenReturn(List.of(fixture(20L, 1, 1, 1, 2), fixture(20L, 1, 2, 3, 4)));

        assertThrows(IllegalArgumentException.class, () -> service.complete(
                new ManualCompetitionDrawService.DrawCommand(20L, 2, 1,
                        List.of(new ManualCompetitionDrawService.Pairing(1, 2),
                                new ManualCompetitionDrawService.Pairing(1, 4)),
                        null, List.of())));
        verify(fixtures, never()).saveAll(anyList());
    }

    @Test
    void persistsEuropeanPairingAndCompletesTheScheduledDrawEvent() {
        Competition loc = competition(40L, 4, "League of Champions");
        when(competitions.findById(40L)).thenReturn(Optional.of(loc));
        when(results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(40L, 0L, 2))
                .thenReturn(List.of());
        when(fixtures.findAllByCompetitionIdAndRoundAndSeasonNumber(40L, 0L, "2"))
                .thenReturn(List.of());
        when(entries.findAllByRoundAndCompetitionIdAndSeasonNumber(0L, 40L, 2))
                .thenReturn(List.of(entry(40L, 0, 1), entry(40L, 0, 2)));
        CalendarEvent draw = drawEvent(40L, 2, 1);
        when(calendarEvents.findAllBySeason(2)).thenReturn(List.of(draw));
        when(calendarEvents.findBySeasonAndCompetitionIdAndMatchday(2, 40L, 1))
                .thenReturn(List.of(draw));

        service.complete(new ManualCompetitionDrawService.DrawCommand(40L, 2, 0,
                List.of(new ManualCompetitionDrawService.Pairing(1, 2)), null, List.of()));

        verify(europeanCompetitions).saveKnockoutPairing(40L, 0L, 1L, 2L, 0);
        verify(scheduling).assignMatchDayForNewRound(40L, 1, 2);
        assertEquals("COMPLETED", draw.getStatus());
        verify(calendarEvents).saveAll(List.of(draw));
    }

    private Competition competition(long id, int type, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setTypeId(type);
        competition.setName(name);
        return competition;
    }

    private CompetitionTeamInfoMatch fixture(long competitionId, long round, int index,
                                              long team1, long team2) {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(competitionId);
        fixture.setRound(round);
        fixture.setMatchIndex(index);
        fixture.setTeam1Id(team1);
        fixture.setTeam2Id(team2);
        fixture.setTeam1Score(-1);
        fixture.setTeam2Score(-1);
        return fixture;
    }

    private CompetitionTeamInfo entry(long competitionId, long round, long teamId) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setCompetitionId(competitionId);
        entry.setRound(round);
        entry.setTeamId(teamId);
        entry.setSeasonNumber(2);
        return entry;
    }

    private CalendarEvent drawEvent(long competitionId, int season, int matchday) {
        CalendarEvent event = new CalendarEvent();
        event.setCompetitionId(competitionId);
        event.setSeason(season);
        event.setMatchday(matchday);
        event.setEventType("EUROPEAN_DRAW");
        event.setStatus("PENDING");
        return event;
    }
}
