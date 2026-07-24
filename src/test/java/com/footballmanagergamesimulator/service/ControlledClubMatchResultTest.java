package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlledClubMatchResultTest {
    @Test
    void contractCarriesFixtureAndBothClubIdsAndDescribesOnlyThatMatch() {
        MatchdayBatchProcessor.ControlledClubMatchResult result =
                MatchdayBatchProcessor.ControlledClubMatchResult.from(Map.of(
                "fixtureId", 901L,
                "team1Id", 11L,
                "team2Id", 22L,
                "team1Name", "Alpha FC",
                "team2Name", "Beta FC",
                "score", "2 - 1"));

        assertThat(result.fixtureId()).isEqualTo(901L);
        assertThat(result.team1Id()).isEqualTo(11L);
        assertThat(result.team2Id()).isEqualTo(22L);
        assertThat(result.description()).isEqualTo("Alpha FC 2 - 1 Beta FC.");
        assertThat(result.description()).doesNotContain("competition");
    }
}
