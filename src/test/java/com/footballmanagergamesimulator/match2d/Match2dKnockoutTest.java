package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The knockout block of a 2D request: the goals each side brings into the match. */
class Match2dKnockoutTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aSingleMatchAsksForKnockoutWithNoAggregate() {
        assertThat(Match2dKnockout.request(0, 0, null).aggregate()).containsExactly(0, 0);
    }

    @Test
    void aSecondLegCarriesTheFirstLegGoalsInItsOwnOrientation() {
        // Leg 1: team1 of leg 1 (= team2 of leg 2) won 3-1; leg 2 is hosted by the leg-1 away side.
        Match2dEngineClient.Knockout knockout = Match2dKnockout.request(2, 7L, new int[]{3, 1});

        assertThat(knockout.aggregate()).containsExactly(1, 3);
    }

    @Test
    void aFirstLegAsksForNoKnockout() {
        assertThat(Match2dKnockout.request(1, 7L, null)).isNull();
    }

    @Test
    void aSecondLegWithoutAKnownFirstLegIsPlayedAsASingleMatch() {
        assertThat(Match2dKnockout.request(2, 7L, null).aggregate()).containsExactly(0, 0);
    }

    @Test
    void theKnockoutFieldIsSerialisedOnlyWhenSet() throws Exception {
        Match2dTeam team = new Match2dTeam(1L, "T", "442", "red", "black", java.util.Map.of(), java.util.List.of());
        String with = json.writeValueAsString(new Match2dEngineClient.Request(team, team, 5L, 90, "9",
                Match2dKnockout.request(2, 7L, new int[]{2, 0}), null));
        String without = json.writeValueAsString(new Match2dEngineClient.Request(team, team, 5L, 90, "9"));

        assertThat(json.readTree(with).get("knockout").get("aggregate").toString()).isEqualTo("[0,2]");
        assertThat(json.readTree(with).has("target")).isFalse();
        assertThat(json.readTree(without).has("knockout")).isFalse();
        assertThat(json.readTree(json.writeValueAsString(Match2dEngineClient.playBody(team, team, 5L, 90,
                Match2dKnockout.request(0, 0, null), null))).get("knockout").get("aggregate").toString())
                .isEqualTo("[0,0]");
        assertThat(Match2dEngineClient.playBody(team, team, 5L, 90)).doesNotContainKeys("knockout", "target");
    }
}
