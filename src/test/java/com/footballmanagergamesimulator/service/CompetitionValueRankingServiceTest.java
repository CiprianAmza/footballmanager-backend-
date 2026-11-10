package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anySet;

class CompetitionValueRankingServiceTest {
    @Test
    void playerRankingShowsCurrentClubAndTitlesWonWithTheClubThatSeason() {
        CompetitionSeasonExpectationRepository expectations = mock(CompetitionSeasonExpectationRepository.class);
        CompetitionHistoryRepository history = mock(CompetitionHistoryRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        TeamCompetitionDetailRepository standings = mock(TeamCompetitionDetailRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        ManagerHistoryRepository managerHistory = mock(ManagerHistoryRepository.class);
        HumanRepository people = mock(HumanRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        GameCalendarRepository calendars = mock(GameCalendarRepository.class);
        CompetitionProgressService progress = mock(CompetitionProgressService.class);
        CompetitionValueRankingService service = new CompetitionValueRankingService(
                expectations, history, competitions, entries, fixtures, standings, results,
                scorers, managerHistory, people, teams, calendars, progress, new CompetitionFormatConfig());

        GameCalendar calendar = new GameCalendar(); calendar.setSeason(3);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        Competition league = new Competition(); league.setId(7); league.setTypeId(Competition.LEAGUE);
        league.setName("Example League");
        when(competitions.findAll()).thenReturn(List.of(league));
        when(history.findAllBySeasonNumber(2)).thenReturn(List.of(history(1, 1), history(2, 2)));
        Team winner = new Team(); winner.setId(1); winner.setName("Winning Club");
        Team newClub = new Team(); newClub.setId(3); newClub.setName("New Club");
        when(teams.findAll()).thenReturn(List.of(winner, newClub));
        Human player = new Human(); player.setId(201); player.setName("Player A"); player.setTeamId(3L);
        when(people.findAllById(anySet())).thenReturn(List.of(player));
        ScorerRepository.ValueAggregate played = mock(ScorerRepository.ValueAggregate.class);
        when(played.getSeasonNumber()).thenReturn(2); when(played.getCompetitionId()).thenReturn(7L);
        when(played.getTeamId()).thenReturn(1L); when(played.getPlayerId()).thenReturn(201L);
        when(played.getAppearances()).thenReturn(10L); when(played.getGoals()).thenReturn(1L);
        when(played.getAssists()).thenReturn(0L);
        when(scorers.aggregateForValueRankings(2)).thenReturn(List.of(played));

        var row = service.rank("players", "season", 2, null).rows().get(0);
        assertEquals("New Club", row.currentTeamName());
        assertEquals(3L, row.currentTeamId());
        assertEquals(List.of(new CompetitionValueRankingService.TrophyWon(2, 7, "Example League")),
                row.trophies());
        assertNull(row.teamId());

        Competition cup = new Competition(); cup.setId(8); cup.setTypeId(Competition.CUP);
        cup.setName("Domestic Cup"); cup.setReputation(6_000);
        when(competitions.findAll()).thenReturn(List.of(cup));
        when(history.findAllBySeasonNumber(2)).thenReturn(List.of(history(8, 1, 1), history(8, 2, 2)));
        ScorerRepository.ValueAggregate cupPlayed = mock(ScorerRepository.ValueAggregate.class);
        when(cupPlayed.getSeasonNumber()).thenReturn(2); when(cupPlayed.getCompetitionId()).thenReturn(8L);
        when(cupPlayed.getTeamId()).thenReturn(1L); when(cupPlayed.getPlayerId()).thenReturn(201L);
        when(cupPlayed.getAppearances()).thenReturn(10L); when(cupPlayed.getGoals()).thenReturn(1L);
        when(cupPlayed.getAssists()).thenReturn(0L);
        when(scorers.aggregateForValueRankings(2)).thenReturn(List.of(cupPlayed));
        var cupRow = service.rank("players", "season", 2, 8L).rows().get(0);
        assertEquals(12.71, cupRow.score(), 0.001);
        assertEquals(List.of(new CompetitionValueRankingService.TrophyWon(2, 8, "Domestic Cup")),
                cupRow.trophies());
    }

    @Test
    void exactOpeningPredictionRewardsOverachievementButOldSeasonDoesNotInventIt() {
        CompetitionSeasonExpectationRepository expectations = mock(CompetitionSeasonExpectationRepository.class);
        CompetitionHistoryRepository history = mock(CompetitionHistoryRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        TeamCompetitionDetailRepository standings = mock(TeamCompetitionDetailRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        ManagerHistoryRepository managerHistory = mock(ManagerHistoryRepository.class);
        HumanRepository people = mock(HumanRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        GameCalendarRepository calendars = mock(GameCalendarRepository.class);
        CompetitionProgressService progress = mock(CompetitionProgressService.class);
        CompetitionValueRankingService service = new CompetitionValueRankingService(
                expectations, history, competitions, entries, fixtures, standings, results,
                scorers, managerHistory, people, teams, calendars, progress, new CompetitionFormatConfig());

        GameCalendar calendar = new GameCalendar(); calendar.setSeason(3);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        Competition league = new Competition(); league.setId(7); league.setTypeId(Competition.LEAGUE);
        league.setName("Example League");
        when(competitions.findAll()).thenReturn(List.of(league));
        Team currentClub = new Team(); currentClub.setId(3); currentClub.setName("New Club");
        Team winningClub = new Team(); winningClub.setId(1); winningClub.setName("Winning Club");
        when(teams.findAll()).thenReturn(List.of(currentClub, winningClub));
        CompetitionHistory winner = history(1, 1);
        CompetitionHistory last = history(2, 2);
        when(history.findAllBySeasonNumber(2)).thenReturn(List.of(winner, last));
        ManagerHistory manager = new ManagerHistory(); manager.setSeasonNumber(2);
        manager.setTeamId(1); manager.setManagerId(101); manager.setManagerName("Manager A");
        manager.setTrophiesWon("Example League");
        Human activeManager = new Human(); activeManager.setId(101); activeManager.setName("Manager A");
        activeManager.setTeamId(3L);
        when(people.findAllByTypeId(anyLong())).thenReturn(List.of(activeManager));
        ManagerHistory other = new ManagerHistory(); other.setSeasonNumber(2);
        other.setTeamId(2); other.setManagerId(102); other.setManagerName("Manager B");
        when(managerHistory.findAll()).thenReturn(List.of(manager, other));

        when(expectations.findAllBySeasonNumber(2)).thenReturn(List.of(expectation(1, 2), expectation(2, 1)));
        var exact = service.rank("managers", "season", 2, 7L);
        assertEquals(2, exact.rows().size());
        assertEquals(101, exact.rows().get(0).personId());
        assertEquals(220, exact.rows().get(0).score(), 0.001);
        assertTrue(exact.rows().get(0).exactOpeningPrediction());
        assertEquals(2, exact.rows().get(0).predictedPosition());
        assertEquals("New Club", exact.rows().get(0).currentTeamName());
        assertEquals(1, exact.rows().get(0).trophies().size());
        var performance = exact.rows().get(0).performances().get(0);
        assertEquals(2, performance.season());
        assertEquals("Winning Club", performance.teamName());
        assertEquals(2, performance.predictedPosition());
        assertEquals(1, performance.actualPosition());
        assertEquals(1, performance.positionDelta());
        assertTrue(performance.trophyWon());
        assertEquals(80, performance.titleBonus(), 0.001);
        assertEquals(exact.rows().get(0).score(), performance.value(), 0.001);
        var underperformer = exact.rows().stream().filter(row -> row.personId() == 102)
                .findFirst().orElseThrow();
        assertEquals(-40, underperformer.score(), 0.001);
        assertEquals(-40, underperformer.performances().get(0).value(), 0.001);
        assertEquals(-1, underperformer.positionDelta());

        when(expectations.findAllBySeasonNumber(2)).thenReturn(List.of());
        var unknown = service.rank("managers", "season", 2, 7L);
        assertEquals(180, unknown.rows().get(0).score(), 0.001);
        assertNull(unknown.rows().get(0).predictedPosition());
        assertFalse(unknown.rows().get(0).exactOpeningPrediction());

        CompetitionTeamInfoDetailRepository.ManagerMatchAggregate first =
                mock(CompetitionTeamInfoDetailRepository.ManagerMatchAggregate.class);
        when(first.getSeasonNumber()).thenReturn(2L); when(first.getCompetitionId()).thenReturn(7L);
        when(first.getTeamId()).thenReturn(1L); when(first.getManagerId()).thenReturn(101L);
        when(first.getMatches()).thenReturn(6L);
        CompetitionTeamInfoDetailRepository.ManagerMatchAggregate replacement =
                mock(CompetitionTeamInfoDetailRepository.ManagerMatchAggregate.class);
        when(replacement.getSeasonNumber()).thenReturn(2L); when(replacement.getCompetitionId()).thenReturn(7L);
        when(replacement.getTeamId()).thenReturn(1L); when(replacement.getManagerId()).thenReturn(103L);
        when(replacement.getMatches()).thenReturn(4L);
        when(results.aggregateHomeManagers(2L)).thenReturn(List.of(first, replacement));
        Human incoming = new Human(); incoming.setId(103); incoming.setName("Replacement");
        when(people.findAllById(anySet())).thenReturn(List.of(incoming));
        var shared = service.rank("managers", "season", 2, 7L);
        assertEquals(108, shared.rows().stream().filter(row -> row.personId() == 101)
                .findFirst().orElseThrow().score(), 0.001);
        assertEquals(72, shared.rows().stream().filter(row -> row.personId() == 103)
                .findFirst().orElseThrow().score(), 0.001);

        Competition missedCup = new Competition(); missedCup.setId(8);
        missedCup.setName("Domestic Cup"); missedCup.setTypeId(Competition.CUP);
        missedCup.setReputation(6_000);
        CompetitionSeasonExpectation cupPrediction = new CompetitionSeasonExpectation();
        cupPrediction.setSeasonNumber(2); cupPrediction.setCompetitionId(8);
        cupPrediction.setTeamId(1); cupPrediction.setFieldSize(2);
        cupPrediction.setPredictedPosition(1);
        when(competitions.findAll()).thenReturn(List.of(missedCup));
        when(history.findAllBySeasonNumber(2)).thenReturn(List.of(history(8, 1, 2), history(8, 2, 1)));
        when(expectations.findAllBySeasonNumber(2)).thenReturn(List.of(cupPrediction));
        var cupMiss = service.rank("managers", "season", 2, 8L).rows().stream()
                .filter(row -> row.personId() == 101).findFirst().orElseThrow();
        assertEquals(-5.04, cupMiss.score(), 0.001);
        assertEquals(-5.04, cupMiss.performances().get(0).value(), 0.001);
        when(expectations.findAllBySeasonNumber(2)).thenReturn(List.of());

        Competition champions = new Competition(); champions.setId(4);
        champions.setName("League of Champions"); champions.setTypeId(Competition.LEAGUE_OF_CHAMPIONS);
        Competition stars = new Competition(); stars.setId(5);
        stars.setName("Stars Cup"); stars.setTypeId(Competition.STARS_CUP);
        Competition superCup = new Competition(); superCup.setId(6);
        superCup.setName("Super Cup"); superCup.setTypeId(Competition.SUPER_CUP);
        Competition cup = new Competition(); cup.setId(8);
        cup.setName("Domestic Cup"); cup.setTypeId(Competition.CUP); cup.setReputation(6_000);
        when(competitions.findAll()).thenReturn(List.of(champions, stars, superCup, cup));
        when(history.findAllBySeasonNumber(2)).thenReturn(List.of(
                history(4, 1, 1), history(4, 4, 2),
                history(5, 2, 1), history(5, 4, 2),
                history(6, 2, 1), history(6, 4, 2),
                history(8, 2, 1), history(8, 4, 2)));
        var hierarchy = service.rank("managers", "season", 2, null);
        var championsWinner = hierarchy.rows().stream().filter(row -> row.personId() == 101)
                .findFirst().orElseThrow();
        var cupWinner = hierarchy.rows().stream().filter(row -> row.personId() == 102)
                .findFirst().orElseThrow();
        assertEquals(234, championsWinner.score(), 0.001);
        assertEquals(251.1, cupWinner.score(), 0.001);
        var championsPerformance = championsWinner.performances().stream()
                .filter(row -> row.competitionId() == 4).findFirst().orElseThrow();
        var cupPerformance = cupWinner.performances().stream()
                .filter(row -> row.competitionId() == 8).findFirst().orElseThrow();
        var superCupPerformance = cupWinner.performances().stream()
                .filter(row -> row.competitionId() == 6).findFirst().orElseThrow();
        assertEquals(1.3, championsPerformance.competitionPrestige(), 0.001);
        assertEquals(1.0, championsPerformance.scoreWeight(), 0.001);
        assertEquals(0.6, cupPerformance.competitionPrestige(), 0.001);
        assertEquals(0.7, cupPerformance.scoreWeight(), 0.001);
        assertEquals(75.6, cupPerformance.value(), 0.001);
        assertEquals(33.6, cupPerformance.titleBonus(), 0.001);
        assertEquals(0.3, superCupPerformance.scoreWeight(), 0.001);
        assertEquals(13.5, superCupPerformance.value(), 0.001);
    }

    private static CompetitionHistory history(long teamId, long position) {
        return history(7, teamId, position);
    }

    private static CompetitionHistory history(long competitionId, long teamId, long position) {
        CompetitionHistory row = new CompetitionHistory(); row.setSeasonNumber(2);
        row.setCompetitionId(competitionId); row.setTeamId(teamId); row.setLastPosition(position);
        row.setGames(10); return row;
    }

    private static CompetitionSeasonExpectation expectation(long teamId, int prediction) {
        CompetitionSeasonExpectation row = new CompetitionSeasonExpectation(); row.setSeasonNumber(2);
        row.setCompetitionId(7); row.setTeamId(teamId); row.setFieldSize(2);
        row.setPredictedPosition(prediction); row.setCompetitionPrestige(1.2); return row;
    }
}
