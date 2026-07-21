package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionHistorySnapshotServiceTest {

    @Test
    void capturesLandscapeValuesAndEntryRoundOnce() {
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionHistorySnapshotService service =
                new CompetitionHistorySnapshotService(teams, humans, entries);

        Team alpha = team(1, 8000); Team beta = team(2, 7000);
        when(teams.findAllById(anyCollection())).thenReturn(List.of(alpha, beta));
        when(humans.findAllByTeamIdIn(anyCollection())).thenReturn(List.of(
                player(1, 240, 100_000_000, 500_000),
                player(1, 220, 80_000_000, 400_000),
                staff(1, 100_000),
                player(2, 210, 60_000_000, 300_000)));
        when(entries.findAllBySeasonNumber(4)).thenReturn(List.of(
                entry(1, 10, 2), entry(2, 10, 1)));

        CompetitionHistory alphaHistory = history(1, 10, 4);
        CompetitionHistory betaHistory = history(2, 10, 4);
        service.capture(List.of(alphaHistory, betaHistory), 4);

        assertTrue(alphaHistory.isLandscapeSnapshotCaptured());
        assertEquals(230.0, alphaHistory.getTopElevenRating());
        assertEquals(180_000_000L, alphaHistory.getSquadValue());
        assertEquals(1_000_000L, alphaHistory.getMonthlyPayroll());
        assertEquals(12_000_000L, alphaHistory.getAnnualPayroll());
        assertEquals(8000, alphaHistory.getReputation());
        assertEquals(2L, alphaHistory.getEntryRound());
        assertEquals(1, alphaHistory.getMediaPrediction());
        assertEquals(2, betaHistory.getMediaPrediction());
    }

    private CompetitionHistory history(long teamId, long competitionId, long season) {
        CompetitionHistory history = new CompetitionHistory();
        history.setTeamId(teamId); history.setCompetitionId(competitionId); history.setSeasonNumber(season);
        return history;
    }

    private Team team(long id, int reputation) {
        Team team = new Team(); team.setId(id); team.setReputation(reputation); return team;
    }

    private Human player(long teamId, double rating, long value, long wage) {
        Human player = new Human(); player.setTeamId(teamId); player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setRating(rating); player.setTransferValue(value); player.setWage(wage); return player;
    }

    private Human staff(long teamId, long wage) {
        Human staff = new Human(); staff.setTeamId(teamId); staff.setTypeId(5); staff.setWage(wage); return staff;
    }

    private CompetitionTeamInfo entry(long teamId, long competitionId, long round) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setTeamId(teamId); entry.setCompetitionId(competitionId); entry.setSeasonNumber(4); entry.setRound(round);
        return entry;
    }
}
