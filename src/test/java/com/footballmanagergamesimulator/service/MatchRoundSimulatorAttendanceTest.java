package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchRoundSimulatorAttendanceTest {

    @Test
    void crowdGoesOnTheFixtureAndOnTheResultWhenItIsAlreadyWritten() {
        CompetitionTeamInfoMatch played = new CompetitionTeamInfoMatch();
        CompetitionTeamInfoDetail playedResult = new CompetitionTeamInfoDetail();
        CompetitionTeamInfoMatch watched = new CompetitionTeamInfoMatch(); // result row comes at /commit
        CompetitionTeamInfoMatch unknownHome = new CompetitionTeamInfoMatch();

        MatchRoundSimulator.storeAttendances(
                Arrays.asList(new FinanceService.MatchAttendance(2_727, 13),
                        new FinanceService.MatchAttendance(15_000, 600), null),
                List.of(played, watched, unknownHome),
                Arrays.asList(playedResult, null, null));

        assertThat(played.getAttendance()).isEqualTo(2_727);
        assertThat(played.getAwayAttendance()).isEqualTo(13);
        assertThat(playedResult.getAttendance()).isEqualTo(2_727);
        assertThat(playedResult.getAwayAttendance()).isEqualTo(13);
        assertThat(watched.getAttendance()).isEqualTo(15_000);
        assertThat(watched.getAwayAttendance()).isEqualTo(600);
        assertThat(unknownHome.getAttendance()).isNull();
        assertThat(unknownHome.getAwayAttendance()).isNull();
    }

    @Test
    void noCrowdsLeaveEverythingUnknown() {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        MatchRoundSimulator.storeAttendances(null, List.of(fixture), List.of());
        MatchRoundSimulator.storeAttendances(List.of(), List.of(fixture), List.of());
        assertThat(fixture.getAttendance()).isNull();
    }
}
