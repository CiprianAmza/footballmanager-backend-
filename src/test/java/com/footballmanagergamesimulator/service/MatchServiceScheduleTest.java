package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.MatchGoalscorersView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchServiceScheduleTest {

    private static final long CLUB = 86;
    private static final long RIVAL = 12;
    private static final long LEAGUE = 1;
    private static final long CUP = 2;
    private static final long LOC = 10;

    private MatchService service;

    @BeforeEach
    void setUp() {
        service = new MatchService();
        service.teamRepository = mock(TeamRepository.class);
        service.competitionRepository = mock(CompetitionRepository.class);
        service.competitionTeamInfoDetailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        service.calendarService = mock(CalendarService.class);
        service.friendlyMatchRepository = mock(FriendlyMatchRepository.class);
        service.friendlyEventRepository = mock(FriendlyEventRepository.class);
        service.competitionProgressService = mock(CompetitionProgressService.class);
        service.competitionDisplayService = mock(CompetitionDisplayService.class);
        service.matchEventRepository = mock(MatchEventRepository.class);
        service.scorerRepository = mock(ScorerRepository.class);
        service.humanRepository = mock(HumanRepository.class);
        when(service.calendarService.getDayOfWeek(anyInt())).thenReturn("Saturday");

        when(service.teamRepository.findNameById(CLUB)).thenReturn("Sherlock FC");
        when(service.teamRepository.findNameById(RIVAL)).thenReturn("Rival United");
        when(service.teamRepository.findStadiumNameById(CLUB)).thenReturn("Baker Street Park");
        when(service.teamRepository.findStadiumNameById(RIVAL)).thenReturn("Rival Ground");
        when(service.competitionRepository.findNameById(LEAGUE)).thenReturn("First League");
        when(service.competitionRepository.findNameById(CUP)).thenReturn("National Cup");
        when(service.competitionRepository.findNameById(LOC)).thenReturn("League of Champions");
        when(service.competitionRepository.findTypeIdById(LEAGUE)).thenReturn(1L);
        when(service.competitionRepository.findTypeIdById(CUP)).thenReturn(2L);
        when(service.competitionRepository.findTypeIdById(LOC)).thenReturn(4L);
        when(service.calendarService.getDateDisplay(anyInt()))
                .thenAnswer(call -> "day " + call.getArgument(0));
        when(service.competitionProgressService.roundLabel(anyLong(), anyLong(), anyLong()))
                .thenAnswer(call -> "label " + call.getArgument(0) + "/" + call.getArgument(1));
    }

    @Test
    void pastSeasonIsRebuiltFromResultsWithReportKeyAndExplicitUnknowns() {
        CompetitionTeamInfoDetail leagueWin = result(LEAGUE, 7, RIVAL, CLUB, "1 - 2", 3);
        leagueWin.setDay(120);
        leagueWin.setDecidedBy("NORMAL");
        leagueWin.setWinnerTeamId(CLUB);
        // Season-4 era cup tie: no calendar day, no tie id, shootout score never stored.
        CompetitionTeamInfoDetail cupFirstLeg = result(CUP, 3, RIVAL, CLUB, "2 - 2 (1st leg)", 3);
        cupFirstLeg.setLegNumber(1);
        cupFirstLeg.setDecidedBy("FIRST_LEG");
        CompetitionTeamInfoDetail cupReturnLeg = result(CUP, 3, CLUB, RIVAL, "1 - 1 (agg 3-3, pens)", 3);
        cupReturnLeg.setLegNumber(2);
        cupReturnLeg.setDecidedBy("PENALTIES");
        cupReturnLeg.setWinnerTeamId(CLUB);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(3, CLUB))
                .thenReturn(List.of(leagueWin, cupReturnLeg, cupFirstLeg));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 3);

        assertThat(schedule).hasSize(3);
        assertThat(schedule.get(0).getLegNumber()).isEqualTo(1);
        ScheduleView cup = schedule.get(1);
        assertThat(cup.getScore()).isEqualTo("1 - 1 (agg 3-3, pens)");
        assertThat(cup.getDay()).isZero();
        assertThat(cup.getDate()).isEqualTo("Matchday 3");
        assertThat(cup.getStatus()).isEqualTo("played");
        assertThat(cup.isReportAvailable()).isTrue();
        assertThat(List.of(cup.getCompetitionId(), (long) cup.getSeasonNumber(), (long) cup.getRoundNumber(),
                cup.getTeamId1(), cup.getTeamId2())).containsExactly(CUP, 3L, 3L, CLUB, RIVAL);
        assertThat(cup.getLegNumber()).isEqualTo(2);
        assertThat(cup.getAggregateTeam1Score()).isEqualTo(3);
        assertThat(cup.getAggregateTeam2Score()).isEqualTo(3);
        assertThat(cup.getPenaltyTeam1Score()).isNull();
        assertThat(cup.getResultOutcome()).isEqualTo("W");
        assertThat(cup.getCompetitionType()).isEqualTo("Cup");
        assertThat(cup.getRoundLabel()).isEqualTo("label 2/3");
        assertThat(cup.getDayOfWeek()).isNull();
        assertThat(cup.getUnknownFields()).containsExactlyInAnyOrder(
                "day", "date", "dayOfWeek", "tieId", "penaltyTeam1Score", "penaltyTeam2Score");

        ScheduleView league = schedule.get(2);
        assertThat(league.getHomeOrAway()).isEqualTo("A");
        assertThat(league.getOpponentTeamId()).isEqualTo(RIVAL);
        assertThat(league.getScore()).isEqualTo("2 - 1");
        assertThat(league.getTeamGoals()).isEqualTo(2);
        assertThat(league.getOpponentGoals()).isEqualTo(1);
        assertThat(league.getResultOutcome()).isEqualTo("W");
        assertThat(league.getDate()).isEqualTo("day 120");
        assertThat(league.getDayOfWeek()).isEqualTo("Saturday");
        assertThat(league.getOpponentLeaguePosition()).isNull();
        assertThat(league.getVenueName()).isEqualTo("Rival Ground");
        assertThat(league.getCompetitionType()).isEqualTo("League");
        assertThat(league.getUnknownFields()).isEmpty();
    }

    @Test
    void currentSeasonKeepsUpcomingFixturesAndJoinsPlayedOnesToTheirResult() {
        CompetitionTeamInfoMatch played = fixture(LOC, 8, CLUB, RIVAL, 200);
        played.setLegNumber(1);
        played.setTieId(55);
        CompetitionTeamInfoMatch upcoming = fixture(LOC, 8, RIVAL, CLUB, 207);
        upcoming.setLegNumber(2);
        upcoming.setTieId(55);
        CompetitionTeamInfoDetail firstLeg = result(LOC, 8, CLUB, RIVAL, "2 - 2 (1st leg)", 15);
        firstLeg.setDecidedBy("FIRST_LEG");
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(15, CLUB))
                .thenReturn(List.of(firstLeg));
        when(service.teamRepository.findCompetitionIdById(RIVAL)).thenReturn(LEAGUE);
        TeamCompetitionView rivalRow = new TeamCompetitionView();
        rivalRow.setTeamId(RIVAL);
        rivalRow.setPosition(3);
        when(service.competitionDisplayService.getTeamDetails(LEAGUE, null)).thenReturn(List.of(rivalRow));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(
                List.of(upcoming, played), CLUB, 15);

        assertThat(schedule).hasSize(2);
        ScheduleView leg1 = schedule.get(0);
        assertThat(leg1.getStatus()).isEqualTo("played");
        assertThat(leg1.getLegNumber()).isEqualTo(1);
        assertThat(leg1.getTieId()).isEqualTo(55);
        assertThat(leg1.getResultOutcome()).isEqualTo("D");
        assertThat(leg1.getScore()).isEqualTo("2 - 2 (1st leg)");
        assertThat(leg1.getAggregateTeam1Score()).isNull();
        assertThat(leg1.getCompetitionType()).isEqualTo("Continental");
        assertThat(leg1.getUnknownFields()).isEmpty();

        ScheduleView leg2 = schedule.get(1);
        assertThat(leg2.getStatus()).isEqualTo("upcoming");
        assertThat(leg2.getScore()).isEqualTo("-");
        assertThat(leg2.getTeamGoals()).isNull();
        assertThat(leg2.getResultOutcome()).isNull();
        assertThat(leg2.isReportAvailable()).isFalse();
        assertThat(leg2.getLegNumber()).isEqualTo(2);
        assertThat(leg2.getDate()).isEqualTo("day 207");
        assertThat(leg2.getOpponentLeaguePosition()).isEqualTo(3);
        assertThat(leg1.getOpponentLeaguePosition()).isNull();
        assertThat(leg2.getUnknownFields()).isEmpty();
    }

    @Test
    void awayScoreFlipsAggregateAndShootoutTextWhileStructuredFieldsStayHomeAligned() {
        CompetitionTeamInfoDetail lostOnPenalties = result(CUP, 4, RIVAL, CLUB, "1 - 1 (agg 2-2, pens 4-3)", 9);
        lostOnPenalties.setDay(150);
        lostOnPenalties.setLegNumber(2);
        lostOnPenalties.setTieId(77);
        lostOnPenalties.setDecidedBy("PENALTIES");
        lostOnPenalties.setWinnerTeamId(RIVAL);
        lostOnPenalties.setPenaltyTeam1Score(4);
        lostOnPenalties.setPenaltyTeam2Score(3);
        lostOnPenalties.setAggregateTeam1Score(2);
        lostOnPenalties.setAggregateTeam2Score(2);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(9, CLUB))
                .thenReturn(List.of(lostOnPenalties));

        ScheduleView view = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 9).get(0);

        assertThat(view.getScore()).isEqualTo("1 - 1 (agg 2-2, pens 3-4)");
        assertThat(view.getResultOutcome()).isEqualTo("L");
        assertThat(view.getPenaltyTeam1Score()).isEqualTo(4);
        assertThat(view.getPenaltyTeam2Score()).isEqualTo(3);
        assertThat(view.getWinnerTeamId()).isEqualTo(RIVAL);
        assertThat(view.getUnknownFields()).isEmpty();
    }

    @Test
    void legacyTieTakesLegsFromDecisionsAndAggregateFromBothLegsNotFromItsText() {
        CompetitionTeamInfoDetail firstLeg = result(CUP, 5, CLUB, RIVAL, "3 - 0 (1st leg)", 2);
        firstLeg.setDay(90);
        firstLeg.setDecidedBy("FIRST_LEG");
        // Older saves wrote AGGREGATE text leg-1-home first, i.e. reversed against this row's team1/team2.
        CompetitionTeamInfoDetail returnLeg = result(CUP, 5, RIVAL, CLUB, "1 - 0 (agg 3-1)", 2);
        returnLeg.setDay(97);
        returnLeg.setDecidedBy("AGGREGATE");
        returnLeg.setWinnerTeamId(CLUB);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(2, CLUB))
                .thenReturn(List.of(returnLeg, firstLeg));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 2);

        assertThat(schedule).extracting(ScheduleView::getLegNumber).containsExactly(1, 2);
        ScheduleView second = schedule.get(1);
        assertThat(second.getScore()).isEqualTo("0 - 1 (agg 3-1)");
        assertThat(second.getResultOutcome()).isEqualTo("L");
        assertThat(second.getAggregateTeam1Score()).isEqualTo(1);
        assertThat(second.getAggregateTeam2Score()).isEqualTo(3);
        assertThat(second.getUnknownFields()).containsExactly("tieId");
    }

    @Test
    void singleMatchDecidersFromOlderSavesUseTheStoredWinner() {
        // Old saves kept extra-time goals out of the score: a level "a.e.t." row still has a winner.
        CompetitionTeamInfoDetail extraTimeWin = result(CUP, 1, RIVAL, CLUB, "0 - 0 (a.e.t.)", 6);
        extraTimeWin.setDay(60);
        extraTimeWin.setDecidedBy("EXTRA_TIME");
        extraTimeWin.setWinnerTeamId(CLUB);
        // Shootout score only in the text: single matches write it home side first.
        CompetitionTeamInfoDetail shootoutLoss = result(CUP, 2, RIVAL, CLUB, "2 - 2 (pens 5-4)", 6);
        shootoutLoss.setDay(80);
        shootoutLoss.setDecidedBy("PENALTIES");
        shootoutLoss.setWinnerTeamId(RIVAL);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(6, CLUB))
                .thenReturn(List.of(shootoutLoss, extraTimeWin));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 6);

        ScheduleView extraTime = schedule.get(0);
        assertThat(extraTime.getScore()).isEqualTo("0 - 0 (a.e.t.)");
        assertThat(extraTime.getResultOutcome()).isEqualTo("W");
        ScheduleView shootout = schedule.get(1);
        assertThat(shootout.getScore()).isEqualTo("2 - 2 (pens 4-5)");
        assertThat(shootout.getPenaltyTeam1Score()).isEqualTo(5);
        assertThat(shootout.getPenaltyTeam2Score()).isEqualTo(4);
        assertThat(shootout.getResultOutcome()).isEqualTo("L");
        assertThat(shootout.getUnknownFields()).isEmpty();
    }

    @Test
    void friendlyRowCarriesScheduleFieldsButNoReport() {
        FriendlyMatch match = new FriendlyMatch();
        match.setId(31);
        match.setSeason(15);
        match.setDay(12);
        match.setHomeTeamId(CLUB);
        match.setAwayTeamId(RIVAL);
        match.setHomeTeamName("Sherlock FC");
        match.setAwayTeamName("Rival United");
        match.setStatus("COMPLETED");
        match.setHomeGoals(0);
        match.setAwayGoals(0);
        match.setFriendlyEventId(4L);
        match.setEventStage("SEMI_FINAL");
        FriendlyEvent event = new FriendlyEvent();
        event.setId(4);
        event.setName("Summer Cup");
        when(service.friendlyMatchRepository.findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(15, CLUB, 15, CLUB))
                .thenReturn(List.of(match));
        when(service.friendlyEventRepository.findAllById(List.of(4L))).thenReturn(List.of(event));

        ScheduleView view = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 15).get(0);

        assertThat(view.getCompetitionType()).isEqualTo("Friendly");
        assertThat(view.getRoundLabel()).isEqualTo("Semi Final");
        assertThat(view.getStatus()).isEqualTo("played");
        assertThat(view.getResultOutcome()).isEqualTo("D");
        assertThat(view.getOpponentTeamId()).isEqualTo(RIVAL);
        assertThat(view.isReportAvailable()).isFalse();
        assertThat(view.getVenueName()).isEqualTo("Baker Street Park");
        assertThat(view.getUnknownFields()).isEmpty();
    }

    @Test
    void summaryScoreIsHomeSideFirstWithTheAggregateSummedFromBothLegs() {
        // Season-2 era return leg: the stored note is leg-1-home first ("2-8"), the home side won 8-2.
        CompetitionTeamInfoDetail firstLeg = result(LOC, 8, RIVAL, CLUB, "1 - 4 (1st leg)", 2);
        firstLeg.setLegNumber(1);
        firstLeg.setDecidedBy("FIRST_LEG");
        CompetitionTeamInfoDetail returnLeg = result(LOC, 8, CLUB, RIVAL, "4 - 1 (agg 2-8)", 2);
        returnLeg.setLegNumber(2);
        returnLeg.setDecidedBy("AGGREGATE");
        returnLeg.setWinnerTeamId(CLUB);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(2, CLUB))
                .thenReturn(List.of(firstLeg, returnLeg));
        CompetitionTeamInfoDetail shootout = result(CUP, 2, CLUB, RIVAL, "1 - 1 (pens 4-3)", 2);
        shootout.setDecidedBy("PENALTIES");
        CompetitionTeamInfoDetail unreadable = result(CUP, 3, CLUB, RIVAL, "walkover", 2);

        assertThat(service.homeScoreText(returnLeg)).isEqualTo("4 - 1 (agg 8-2)");
        assertThat(service.homeScoreText(firstLeg)).isEqualTo("1 - 4 (1st leg)");
        assertThat(service.homeScoreText(shootout)).isEqualTo("1 - 1 (pens 4-3)");
        assertThat(service.homeScoreText(unreadable)).isEqualTo("walkover");
        assertThat(service.homeScoreText(null)).isNull();
    }

    @Test
    void seasonGoalscorersUseEventsThenScorerCountsAndSkipLegsTheyCannotTellApart() {
        CompetitionTeamInfoDetail leagueWin = result(LEAGUE, 4, CLUB, RIVAL, "2 - 1", 15);
        leagueWin.setDay(50);
        CompetitionTeamInfoDetail cupWin = result(CUP, 2, RIVAL, CLUB, "0 - 2", 15);
        cupWin.setDay(60);
        CompetitionTeamInfoDetail firstLeg = result(LOC, 8, CLUB, RIVAL, "1 - 1 (1st leg)", 15);
        firstLeg.setDay(70);
        firstLeg.setLegNumber(1);
        CompetitionTeamInfoDetail returnLeg = result(LOC, 8, RIVAL, CLUB, "1 - 1 (agg 2-2, pens 4-2)", 15);
        returnLeg.setDay(77);
        returnLeg.setLegNumber(2);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(15, CLUB))
                .thenReturn(List.of(returnLeg, cupWin, leagueWin, firstLeg));
        when(service.matchEventRepository.findGoalsBySeasonNumberAndTeamId(15, CLUB)).thenReturn(List.of(
                goalEvent(LEAGUE, 4, RIVAL, 20, "Visitor", 80, "PENALTY"),
                goalEvent(LEAGUE, 4, CLUB, 10, "Captain", 12, "Penalty"),
                goalEvent(LEAGUE, 4, CLUB, 11, "Striker", 70, "Volley")));
        // Both legs ended 1-1 in the same round: their Scorer rows share one key.
        when(service.scorerRepository.findGoalRowsBySeasonNumberAndTeamId(15, CLUB)).thenReturn(List.of(
                scorerRow(CUP, 2, CLUB, RIVAL, 2, 0, 11, 2),
                scorerRow(LOC, 8, CLUB, RIVAL, 1, 1, 11, 1),
                scorerRow(LOC, 8, RIVAL, CLUB, 1, 1, 20, 1)));
        Human striker = new Human();
        striker.setId(11);
        striker.setName("Striker");
        when(service.humanRepository.findAllById(any())).thenReturn(List.of(striker));

        List<MatchGoalscorersView> views = service.getSeasonGoalscorers(15, CLUB);

        assertThat(views).extracting(MatchGoalscorersView::getCompetitionId).containsExactly(LEAGUE, CUP, LOC, LOC);
        MatchGoalscorersView league = views.get(0);
        assertThat(league.getGoals()).extracting(MatchGoalscorersView.Goal::getMinute).containsExactly(12, 70, 80);
        assertThat(league.getGoals()).extracting(MatchGoalscorersView.Goal::getPenalty).containsExactly(true, false, true);
        assertThat(league.getUnknownFields()).isEmpty();
        MatchGoalscorersView cup = views.get(1);
        assertThat(cup.getGoals()).hasSize(2).allSatisfy(goal -> {
            assertThat(goal.getPlayerName()).isEqualTo("Striker");
            assertThat(goal.getTeamId()).isEqualTo(CLUB);
            assertThat(goal.getMinute()).isNull();
            assertThat(goal.getPenalty()).isNull();
        });
        assertThat(cup.getUnknownFields()).containsExactly("minute", "penalty");
        assertThat(views.subList(2, 4)).allSatisfy(leg -> {
            assertThat(leg.getGoals()).isEmpty();
            assertThat(leg.getUnknownFields()).containsExactly("goals");
        });
    }

    @Test
    void competitionTypesFollowTheCompetitionKind() {
        assertThat(MatchService.scheduleCompetitionType(1L)).isEqualTo("League");
        assertThat(MatchService.scheduleCompetitionType(3L)).isEqualTo("League");
        assertThat(MatchService.scheduleCompetitionType(2L)).isEqualTo("Cup");
        assertThat(MatchService.scheduleCompetitionType(6L)).isEqualTo("Cup");
        assertThat(MatchService.scheduleCompetitionType(4L)).isEqualTo("Continental");
        assertThat(MatchService.scheduleCompetitionType(5L)).isEqualTo("Continental");
        assertThat(MatchService.scheduleCompetitionType(99L)).isNull();
        assertThat(MatchService.scheduleCompetitionType(null)).isNull();
    }

    private static CompetitionTeamInfoDetail result(long competitionId, long round, long team1, long team2,
                                                    String score, long season) {
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setCompetitionId(competitionId);
        detail.setRoundId(round);
        detail.setTeam1Id(team1);
        detail.setTeam2Id(team2);
        detail.setScore(score);
        detail.setSeasonNumber(season);
        return detail;
    }

    private static MatchEvent goalEvent(long competitionId, int round, long scoringTeam, long playerId,
                                        String playerName, int minute, String details) {
        MatchEvent event = new MatchEvent();
        event.setCompetitionId(competitionId);
        event.setSeasonNumber(15);
        event.setRoundNumber(round);
        event.setTeamId1(CLUB);
        event.setTeamId2(RIVAL);
        event.setEventType("goal");
        event.setTeamId(scoringTeam);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setMinute(minute);
        event.setDetails(details);
        return event;
    }

    private static Scorer scorerRow(long competitionId, int round, long teamId, long opponentTeamId,
                                    int teamScore, int opponentScore, long playerId, int goals) {
        Scorer row = new Scorer();
        row.setCompetitionId(competitionId);
        row.setSeasonNumber(15);
        row.setRoundNumber(round);
        row.setTeamId(teamId);
        row.setOpponentTeamId(opponentTeamId);
        row.setTeamScore(teamScore);
        row.setOpponentScore(opponentScore);
        row.setPlayerId(playerId);
        row.setGoals(goals);
        return row;
    }

    private static CompetitionTeamInfoMatch fixture(long competitionId, long round, long team1, long team2, int day) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setCompetitionId(competitionId);
        match.setRound(round);
        match.setTeam1Id(team1);
        match.setTeam2Id(team2);
        match.setDay(day);
        match.setSeasonNumber("15");
        return match;
    }
}
