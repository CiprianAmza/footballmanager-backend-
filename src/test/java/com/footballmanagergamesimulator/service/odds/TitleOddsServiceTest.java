package com.footballmanagergamesimulator.service.odds;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.TitleOddsSnapshot;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.LeagueQualificationDisplayService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TitleOddsServiceTest {

    private static final long LEAGUE = 50;
    private static final int SEASON = 3;

    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
    private final TeamCompetitionDetailRepository tables = mock(TeamCompetitionDetailRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final RoundRepository rounds = mock(RoundRepository.class);
    private final TitleOddsSnapshotRepository snapshots = mock(TitleOddsSnapshotRepository.class);
    private final LeagueQualificationDisplayService qualification = mock(LeagueQualificationDisplayService.class);
    private final TacticSimulationService engine = mock(TacticSimulationService.class);
    private final UserContext users = mock(UserContext.class);
    private final TitleOddsService odds = new TitleOddsService(competitions, fixtures,
            mock(CompetitionTeamInfoDetailRepository.class), tables, teams, rounds, snapshots, qualification, engine, users);

    private final Competition league = new Competition();
    private final List<CompetitionTeamInfoMatch> season = new ArrayList<>();
    private final List<TitleOddsSnapshot> stored = new ArrayList<>();

    @BeforeEach
    void world() {
        league.setId(LEAGUE);
        league.setTypeId(Competition.LEAGUE);
        league.setTier(1);
        when(competitions.findById(LEAGUE)).thenReturn(Optional.of(league));
        Round round = new Round();
        round.setSeason(SEASON);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(teams.findNameById(anyLong())).thenAnswer(call -> "Club " + call.getArgument(0));
        when(users.getAllHumanTeamIds()).thenReturn(List.of(1L));
        // The engine cannot line these clubs up: the plain home edge is used (45% / 27% / 28%).
        when(engine.currentTeamEvaluation(anyLong())).thenThrow(new IllegalStateException("no eleven"));
        when(qualification.context(LEAGUE, SEASON)).thenReturn(new LeagueQualificationDisplayService.LeagueQualificationContext(
                LEAGUE, SEASON, true, 3, 1, 0, null, null, null, null, null, List.of()));

        // 1 beat 2 2-0, 2 and 3 drew; four matches left.
        season.add(fixture(1, 2, 2, 0));
        season.add(fixture(2, 3, 1, 1));
        season.add(fixture(3, 1, -1, -1));
        season.add(fixture(2, 1, -1, -1));
        season.add(fixture(3, 2, -1, -1));
        season.add(fixture(1, 3, -1, -1));
        when(fixtures.findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(LEAGUE, "3")).thenReturn(season);
        when(tables.findAllByCompetitionIdAndTeamIdIn(eq(LEAGUE), anyCollection())).thenReturn(List.of(
                row(1, 1, 3, 2, 2), row(2, 2, 1, -2, 1), row(3, 1, 1, 0, 1)));

        when(snapshots.findAllByCompetitionIdAndSeasonNumberAndGamesPlayed(anyLong(), anyInt(), anyInt()))
                .thenAnswer(call -> stored.stream().filter(s -> s.getGamesPlayed() == (int) call.getArgument(2)).toList());
        when(snapshots.saveAll(anyList())).thenAnswer(call -> { stored.addAll(call.getArgument(0)); return call.getArgument(0); });
        doAnswer(call -> { stored.removeAll((List<?>) call.getArgument(0)); return null; }).when(snapshots).deleteAll(anyList());
    }

    @Test
    @DisplayName("""
            GIVEN a 3-team top flight (1 League of Champions place, relegation from 3rd): club 1 on 3 points, club 3 on
             1 (goal difference 0), club 2 on 1 (-2), four matches left
            WHEN the league's chances are asked for twice, with no new result in between
            THEN the rows follow the table, the chances add up, each club is lined up once, the second answer is the
             same one, and the title race keeps one point per club for 2 games played
            """)
    void chancesFollowTheTableAndAreKeptUntilTheNextResult() {
        TitleOddsService.LeagueOdds first = odds.leagueOdds(LEAGUE);
        TitleOddsService.LeagueOdds again = odds.leagueOdds(LEAGUE);

        assertSame(first, again);
        assertEquals(List.of(1L, 3L, 2L), first.rows().stream().map(TitleOddsService.LeagueRow::teamId).toList());
        assertEquals(4, first.fixturesLeft());
        assertEquals(1, first.championsSpots());
        assertEquals(3, first.relegationFrom());
        assertEquals(2, first.gamesPlayed());
        assertEquals(1.0, first.rows().stream().mapToDouble(TitleOddsService.LeagueRow::title).sum(), 1e-9);
        assertEquals(1.0, first.rows().stream().mapToDouble(TitleOddsService.LeagueRow::relegation).sum(), 1e-9);
        assertTrue(first.rows().get(0).title() > first.rows().get(2).title());
        verify(engine, times(3)).currentTeamEvaluation(anyLong());
        assertEquals(3, stored.size());
        assertTrue(stored.stream().allMatch(s -> s.getGamesPlayed() == 2 && s.getSeasonNumber() == SEASON));
    }

    @Test
    @DisplayName("""
            GIVEN the chances already worked out after 2 games
            WHEN a new match is played (club 3 beats club 1) and the chances are asked for again
            THEN they are worked out again, and the title race point for the same games played is replaced, not
             added twice
            """)
    void aNewResultRecomputesAndReplacesTheRacePoint() {
        TitleOddsService.LeagueOdds before = odds.leagueOdds(LEAGUE);
        season.get(2).setTeam1Score(1);
        season.get(2).setTeam2Score(0);

        TitleOddsService.LeagueOdds after = odds.leagueOdds(LEAGUE);

        assertNotSame(before, after);
        assertEquals(3, after.fixturesLeft());
        assertEquals(3, stored.size());
    }

    @Test
    @DisplayName("""
            GIVEN the same league as a second tier
            WHEN its chances are worked out
            THEN it has no League of Champions places (the top flight's places are not its own)
            """)
    void aSecondTierHasNoLeagueOfChampionsPlaces() {
        league.setTier(2);

        assertEquals(0, odds.leagueOdds(LEAGUE).championsSpots());
    }

    @Test
    @DisplayName("""
            GIVEN a matchday in this league and in another one where no human manager has a club
            WHEN the title race is recorded after the matchday
            THEN only this league is worked out
            """)
    void onlyLeaguesWithAHumanManagerAreRecordedAfterAMatchday() {
        Competition other = new Competition();
        other.setId(60);
        other.setTypeId(Competition.LEAGUE);
        other.setTier(1);
        when(competitions.findById(60L)).thenReturn(Optional.of(other));
        when(tables.findAllByCompetitionIdAndTeamIdIn(eq(60L), anyCollection())).thenReturn(List.of());

        odds.recordAfterMatchday(List.of(LEAGUE, 60L, LEAGUE));

        verify(fixtures, times(1)).findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(LEAGUE, "3");
        verify(fixtures, never()).findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(eq(60L), anyString());
        assertEquals(3, stored.size());
    }

    private static CompetitionTeamInfoMatch fixture(long home, long away, int homeGoals, int awayGoals) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setCompetitionId(LEAGUE);
        match.setSeasonNumber(String.valueOf(SEASON));
        match.setTeam1Id(home);
        match.setTeam2Id(away);
        match.setTeam1Score(homeGoals);
        match.setTeam2Score(awayGoals);
        return match;
    }

    private static TeamCompetitionDetail row(long teamId, int games, int points, int goalDifference, int goalsFor) {
        TeamCompetitionDetail row = new TeamCompetitionDetail();
        row.setTeamId(teamId);
        row.setCompetitionId(LEAGUE);
        row.setGames(games);
        row.setPoints(points);
        row.setGoalDifference(goalDifference);
        row.setGoalsFor(goalsFor);
        return row;
    }
}
