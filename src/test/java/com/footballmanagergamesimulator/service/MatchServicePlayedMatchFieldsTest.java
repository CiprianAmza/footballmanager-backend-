package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.MatchGoalscorersView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.MatchEvent;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchServicePlayedMatchFieldsTest {

    private static final long CLUB = 86;
    private static final long RIVAL = 12;
    private static final long OTHER = 30;
    private static final long FOURTH = 40;
    private static final long LEAGUE = 1;
    private static final long CUP = 2;

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
        when(service.calendarService.getDateDisplay(anyInt())).thenAnswer(call -> "day " + call.getArgument(0));
        when(service.competitionProgressService.roundLabel(anyLong(), anyLong(), anyLong())).thenReturn("label");
        when(service.competitionRepository.findTypeIdById(LEAGUE)).thenReturn(1L);
        when(service.competitionRepository.findTypeIdById(CUP)).thenReturn(2L);
    }

    @Test
    void leaguePositionIsTheTableOfTheResultsBeforeTheMatchDay() {
        List<CompetitionTeamInfoDetail> league = List.of(
                result(LEAGUE, 1, CLUB, RIVAL, "2 - 0", 10),
                result(LEAGUE, 1, OTHER, FOURTH, "1 - 1", 10),
                result(LEAGUE, 2, RIVAL, CLUB, "3 - 0", 20),
                result(LEAGUE, 2, FOURTH, OTHER, "walkover", 20),
                result(LEAGUE, 3, CLUB, OTHER, "1 - 0", 30));

        // Day 15: CLUB 3 pts; OTHER and FOURTH 1 pt each, level on everything (lower id first); RIVAL 0.
        assertThat(MatchService.leaguePositionBefore(league, CLUB, 15)).isEqualTo(1);
        assertThat(MatchService.leaguePositionBefore(league, OTHER, 15)).isEqualTo(2);
        assertThat(MatchService.leaguePositionBefore(league, FOURTH, 15)).isEqualTo(3);
        assertThat(MatchService.leaguePositionBefore(league, RIVAL, 15)).isEqualTo(4);
        // Day 25: RIVAL and CLUB on 3 pts, RIVAL ahead on goal difference; the walkover is not counted.
        assertThat(MatchService.leaguePositionBefore(league, RIVAL, 25)).isEqualTo(1);
        assertThat(MatchService.leaguePositionBefore(league, CLUB, 25)).isEqualTo(2);
        // A match-day result is not part of the table going into that day.
        assertThat(MatchService.leaguePositionBefore(league, OTHER, 30)).isEqualTo(3);
    }

    @Test
    void leaguePositionIsUnknownWithoutADayATableOrTheClub() {
        List<CompetitionTeamInfoDetail> league = List.of(result(LEAGUE, 1, CLUB, RIVAL, "2 - 0", 10));
        assertThat(MatchService.leaguePositionBefore(league, CLUB, 0)).isNull();
        assertThat(MatchService.leaguePositionBefore(league, CLUB, 10)).isNull();
        assertThat(MatchService.leaguePositionBefore(league, OTHER, 15)).isNull();
        assertThat(MatchService.leaguePositionBefore(null, CLUB, 15)).isNull();
        CompetitionTeamInfoDetail noDay = result(LEAGUE, 1, CLUB, RIVAL, "2 - 0", 0);
        assertThat(MatchService.leaguePositionBefore(List.of(noDay), CLUB, 15)).isNull();
    }

    @Test
    void playedRowsCarryTheOpponentsPositionInItsOwnLeagueOnTheMatchDay() {
        CompetitionTeamInfoDetail cupTie = result(CUP, 1, CLUB, RIVAL, "1 - 0", 25);
        CompetitionTeamInfoDetail oldCupTie = result(CUP, 2, RIVAL, CLUB, "0 - 2", 0);
        CompetitionTeamInfoDetail rivalLeague1 = result(LEAGUE, 1, RIVAL, OTHER, "0 - 1", 10);
        CompetitionTeamInfoDetail rivalLeague2 = result(LEAGUE, 2, FOURTH, RIVAL, "2 - 2", 20);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(5, CLUB))
                .thenReturn(List.of(cupTie, oldCupTie));
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(5, RIVAL))
                .thenReturn(List.of(cupTie, rivalLeague1, rivalLeague2));
        when(service.competitionTeamInfoDetailRepository.findAllByCompetitionIdAndSeasonNumber(LEAGUE, 5))
                .thenReturn(List.of(rivalLeague1, rivalLeague2));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), CLUB, 5);

        ScheduleView played = schedule.stream().filter(view -> view.getDay() == 25).findFirst().orElseThrow();
        // Going into day 25: OTHER 3 pts, FOURTH 1 pt (+0, 2 scored), RIVAL 1 pt (-1).
        assertThat(played.getOpponentLeaguePosition()).isEqualTo(3);
        assertThat(played.getUnknownFields()).doesNotContain("opponentLeaguePosition");
        ScheduleView noDay = schedule.stream().filter(view -> view.getDay() == 0).findFirst().orElseThrow();
        assertThat(noDay.getOpponentLeaguePosition()).isNull();
        assertThat(noDay.getUnknownFields()).doesNotContain("opponentLeaguePosition");
    }

    @Test
    void seasonGoalscorersCarryStoppageMinutesInTimelineOrder() {
        CompetitionTeamInfoDetail match = result(LEAGUE, 4, CLUB, RIVAL, "2 - 1", 40);
        when(service.competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(5, CLUB))
                .thenReturn(List.of(match));
        when(service.matchEventRepository.findGoalsBySeasonNumberAndTeamId(5, CLUB)).thenReturn(List.of(
                goal(CLUB, 7, 45, 2), goal(RIVAL, 8, 46, null), goal(CLUB, 9, 45, null)));

        MatchGoalscorersView view = service.getSeasonGoalscorers(5, CLUB).get(0);

        assertThat(view.getGoals()).extracting(MatchGoalscorersView.Goal::getPlayerId).containsExactly(9L, 7L, 8L);
        assertThat(view.getGoals()).extracting(MatchGoalscorersView.Goal::getMinute).containsExactly(45, 45, 46);
        assertThat(view.getGoals()).extracting(MatchGoalscorersView.Goal::getAddedTime).containsExactly(null, 2, null);
        assertThat(view.getUnknownFields()).isEmpty();
    }

    private static CompetitionTeamInfoDetail result(long competitionId, long round, long team1, long team2,
                                                    String score, int day) {
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setCompetitionId(competitionId);
        detail.setRoundId(round);
        detail.setTeam1Id(team1);
        detail.setTeam2Id(team2);
        detail.setScore(score);
        detail.setSeasonNumber(5);
        detail.setDay(day);
        return detail;
    }

    private static MatchEvent goal(long teamId, long playerId, int minute, Integer addedTime) {
        MatchEvent event = new MatchEvent();
        event.setCompetitionId(LEAGUE);
        event.setSeasonNumber(5);
        event.setRoundNumber(4);
        event.setTeamId1(CLUB);
        event.setTeamId2(RIVAL);
        event.setEventType("goal");
        event.setTeamId(teamId);
        event.setPlayerId(playerId);
        event.setPlayerName("P" + playerId);
        event.setMinute(minute);
        event.setAddedTime(addedTime);
        event.setDetails("OPEN_PLAY");
        return event;
    }
}
