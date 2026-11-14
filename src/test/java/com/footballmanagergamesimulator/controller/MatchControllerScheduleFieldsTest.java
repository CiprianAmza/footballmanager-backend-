package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.MatchSummaryView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchService;
import com.footballmanagergamesimulator.service.MatchStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchControllerScheduleFieldsTest {

    private MatchController controller;

    @BeforeEach
    void setUp() {
        controller = new MatchController();
        controller.teamRepository = mock(TeamRepository.class);
        controller.competitionTeamInfoDetailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        controller.matchEventRepository = mock(MatchEventRepository.class);
        controller.scorerRepository = mock(ScorerRepository.class);
        controller.humanRepository = mock(HumanRepository.class);
        controller.matchService = mock(MatchService.class);
        controller.matchStatsService = mock(MatchStatsService.class);
    }

    @Test
    void summaryCarriesAttendanceAndStoppageMinutesInTimelineOrder() {
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setScore("2 - 1");
        detail.setAttendance(2_727);
        detail.setAwayAttendance(13);
        when(controller.competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(1, 20, 86, 12, 15))
                .thenReturn(List.of(detail));
        when(controller.matchService.homeScoreText(detail)).thenReturn("2 - 1");
        when(controller.matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(1, 15, 20, 86, 12))
                .thenReturn(new ArrayList<>(List.of(
                        event("goal", 86, 7, 45, 2), event("injury", 86, 5, 90, null),
                        event("goal", 12, 8, 45, null), event("goal", 86, 9, 30, null))));

        MatchSummaryView summary = controller.getMatchSummary(1, 15, 20, 86, 12);

        assertThat(summary.getAttendance()).isEqualTo(2_727);
        assertThat(summary.getAwayAttendance()).isEqualTo(13);
        assertThat(summary.getGoals()).extracting(MatchSummaryView.GoalDetail::getPlayerId).containsExactly(9L, 8L, 7L);
        assertThat(summary.getGoals()).extracting(MatchSummaryView.GoalDetail::getMinute).containsExactly(30, 45, 45);
        assertThat(summary.getGoals()).extracting(MatchSummaryView.GoalDetail::getAddedTime)
                .containsExactly(null, null, 2);
    }

    @Test
    void summaryAttendanceIsUnknownForOlderResults() {
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setScore("0 - 0");
        detail.setAwayAttendance(13); // without a crowd the away figure means nothing
        when(controller.competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(1, 20, 86, 12, 15))
                .thenReturn(List.of(detail));

        MatchSummaryView summary = controller.getMatchSummary(1, 15, 20, 86, 12);

        assertThat(summary.getAttendance()).isNull();
        assertThat(summary.getAwayAttendance()).isNull();
    }

    @Test
    void matchEventsComeInTimelineOrderWithInjuries() {
        when(controller.matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(1, 15, 20, 86, 12))
                .thenReturn(new ArrayList<>(List.of(
                        event("injury", 86, 5, 90, null), event("goal", 86, 7, 90, 3),
                        event("goal", 12, 8, 46, null), event("goal", 86, 9, 45, 1))));

        List<MatchEvent> events = controller.getMatchEvents(1, 15, 20, 86, 12);

        assertThat(events).extracting(MatchEvent::getPlayerId).containsExactly(9L, 8L, 5L, 7L);
        assertThat(events).extracting(MatchEvent::getEventType).containsExactly("goal", "goal", "injury", "goal");
    }

    private static MatchEvent event(String type, long teamId, long playerId, int minute, Integer addedTime) {
        MatchEvent event = new MatchEvent();
        event.setEventType(type);
        event.setTeamId(teamId);
        event.setPlayerId(playerId);
        event.setPlayerName("P" + playerId);
        event.setMinute(minute);
        event.setAddedTime(addedTime);
        return event;
    }
}
