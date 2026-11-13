package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(cup.getUnknownFields()).containsExactlyInAnyOrder(
                "day", "date", "tieId", "penaltyTeam1Score", "penaltyTeam2Score");

        ScheduleView league = schedule.get(2);
        assertThat(league.getHomeOrAway()).isEqualTo("A");
        assertThat(league.getOpponentTeamId()).isEqualTo(RIVAL);
        assertThat(league.getScore()).isEqualTo("2 - 1");
        assertThat(league.getTeamGoals()).isEqualTo(2);
        assertThat(league.getOpponentGoals()).isEqualTo(1);
        assertThat(league.getResultOutcome()).isEqualTo("W");
        assertThat(league.getDate()).isEqualTo("day 120");
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
