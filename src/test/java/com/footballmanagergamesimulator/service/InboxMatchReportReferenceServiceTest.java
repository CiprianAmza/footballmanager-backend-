package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboxMatchReportReferenceServiceTest {

    @Test
    void legacyScoreMessageIsLinkedToItsExactStoredMatch() {
        MatchStatsRepository matches = mock(MatchStatsRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        InboxMatchReportReferenceService service = new InboxMatchReportReferenceService(matches, teams);
        ManagerInbox message = message("Victory! Sherlock FC 2-1 Yu Gi Oh");
        MatchStats match = match();
        when(matches.findAllBySeasonNumberAndRoundNumber(4, 12)).thenReturn(List.of(match));
        when(teams.findNameById(86L)).thenReturn("Sherlock FC");
        when(teams.findNameById(91L)).thenReturn("Yu Gi Oh");

        service.attachMissingReferences(List.of(message));

        assertEquals("MATCH_REPORT_V1|8|4|12|86|91|86", message.getDeduplicationKey());
    }

    @Test
    void unrelatedMatchNoticeIsNotGuessed() {
        MatchStatsRepository matches = mock(MatchStatsRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        InboxMatchReportReferenceService service = new InboxMatchReportReferenceService(matches, teams);
        ManagerInbox message = message("Derby Result");
        when(matches.findAllBySeasonNumberAndRoundNumber(4, 12)).thenReturn(List.of(match()));
        when(teams.findNameById(86L)).thenReturn("Sherlock FC");
        when(teams.findNameById(91L)).thenReturn("Yu Gi Oh");

        service.attachMissingReferences(List.of(message));

        assertNull(message.getDeduplicationKey());
    }

    private static ManagerInbox message(String title) {
        ManagerInbox message = new ManagerInbox();
        message.setTeamId(86L);
        message.setSeasonNumber(4);
        message.setRoundNumber(12);
        message.setCategory("match_result");
        message.setTitle(title);
        return message;
    }

    private static MatchStats match() {
        MatchStats match = new MatchStats();
        match.setCompetitionId(8L);
        match.setSeasonNumber(4);
        match.setRoundNumber(12);
        match.setTeam1Id(86L);
        match.setTeam2Id(91L);
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        return match;
    }
}
