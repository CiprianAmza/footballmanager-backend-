package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The knockout block of the 2D engine: what is asked for and how it decides the tie. */
class Match2dKnockoutTest {

    private static final long TEAM1 = 10L;
    private static final long TEAM2 = 20L;

    private final ObjectMapper json = new ObjectMapper();

    private Match2dResult result(String body) throws Exception {
        return json.readValue(body, Match2dResult.class);
    }

    // ---- request ----

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
                Match2dKnockout.request(2, 7L, new int[]{2, 0})));
        String without = json.writeValueAsString(new Match2dEngineClient.Request(team, team, 5L, 90, "9"));

        assertThat(json.readTree(with).get("knockout").get("aggregate").toString()).isEqualTo("[0,2]");
        assertThat(json.readTree(without).has("knockout")).isFalse();
        assertThat(json.readTree(json.writeValueAsString(Match2dEngineClient.playBody(team, team, 5L, 90,
                Match2dKnockout.request(0, 0, null)))).get("knockout").get("aggregate").toString()).isEqualTo("[0,0]");
        assertThat(Match2dEngineClient.playBody(team, team, 5L, 90)).doesNotContainKey("knockout");
    }

    // ---- single match ----

    @Test
    void aSingleMatchWonInRegularTimeIsNormal() throws Exception {
        Match2dResult r = result("""
                {"score":[2,1],"goals":[{"minute":10,"team":0},{"minute":20,"team":1},{"minute":80,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[2,1],"extraTime":false,"extraTimeGoals":[0,0],
                             "penalties":null,"winner":0,"decidedBy":"regular"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 0, 0, null, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(2, 1, 2, 1, "", TEAM1, "NORMAL",
                null, null, null, null, null, null));
    }

    @Test
    void aSingleMatchWonInExtraTimeCarriesTheExtraTimeGoals() throws Exception {
        Match2dResult r = result("""
                {"score":[1,2],"goals":[{"minute":10,"team":0},{"minute":60,"team":1},{"minute":104,"team":1}],
                 "knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[0,1],
                             "winner":1,"decidedBy":"extra_time"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 0, 0, null, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(1, 1, 1, 2, " (a.e.t.)", TEAM2, "EXTRA_TIME",
                null, null, null, null, 0, 1));
    }

    @Test
    void aSingleMatchWonOnPenaltiesKeepsTheShootoutApartFromTheScore() throws Exception {
        Match2dResult r = result("""
                {"score":[2,2],"goals":[{"minute":10,"team":0},{"minute":60,"team":1},
                                        {"minute":100,"team":1},{"minute":115,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,1],
                             "penalties":[4,3],"shootout":[{"team":0,"uid":1,"playerId":101,"player":"A","scored":true}],
                             "winner":0,"decidedBy":"penalties"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 0, 0, null, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(1, 1, 2, 2, " (pens 4-3)", TEAM1, "PENALTIES",
                4, 3, null, null, 1, 1));
        assertThat(r.knockout().shootout()).hasSize(1);
    }

    // ---- second leg ----

    @Test
    void aSecondLegDecidedOnAggregateNamesTheWinnerAcrossBothLegs() throws Exception {
        // Leg 1: 2-0 for team2 of this match (it hosted leg 1). Leg 2: team1 wins 1-0, out 1-2 on aggregate.
        Match2dResult r = result("""
                {"score":[1,0],"goals":[{"minute":30,"team":0}],
                 "knockout":{"aggregate":[0,2],"score90":[1,0],"extraTime":false,
                             "winner":1,"decidedBy":"regular"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 2, 7L, new int[]{2, 0}, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(1, 0, 1, 0, " (agg 1-2)", TEAM2, "AGGREGATE",
                null, null, 1, 2, null, null));
    }

    @Test
    void aSecondLegWonInExtraTimeAddsTheExtraTimeGoalsToTheLegAndTheAggregate() throws Exception {
        // Leg 1: 1-0 for the leg-1 host (team2 here). Leg 2: 1-0 after 90', 2-0 after extra time.
        Match2dResult r = result("""
                {"score":[2,0],"goals":[{"minute":50,"team":0},{"minute":95,"team":0}],
                 "knockout":{"aggregate":[0,1],"score90":[1,0],"extraTime":true,"extraTimeGoals":[1,0],
                             "winner":0,"decidedBy":"extra_time"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 2, 7L, new int[]{1, 0}, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(1, 0, 2, 0, " (agg 2-1, a.e.t.)", TEAM1,
                "EXTRA_TIME", null, null, 2, 1, 1, 0));
    }

    @Test
    void aSecondLegWonOnPenaltiesNamesTheShootoutWinner() throws Exception {
        // Leg 1: 2-1 for the leg-1 host (team2 here); leg 2: 1-0 after 90' (agg 2-2), 1-1 in extra time (agg 3-3).
        Match2dResult r = result("""
                {"score":[2,1],"goals":[{"minute":5,"team":0},{"minute":101,"team":1},{"minute":118,"team":0}],
                 "knockout":{"aggregate":[1,2],"score90":[1,0],"extraTime":true,"extraTimeGoals":[1,1],
                             "penalties":[2,4],"winner":1,"decidedBy":"penalties"}}""");

        Match2dKnockout.Decision d = Match2dKnockout.decide(r, 2, 7L, new int[]{2, 1}, TEAM1, TEAM2).decision();

        assertThat(d).isEqualTo(new Match2dKnockout.Decision(1, 0, 2, 1, " (agg 3-3, pens 2-4)", TEAM2,
                "PENALTIES", 2, 4, 3, 3, 1, 1));
    }

    // ---- fallback ----

    @Test
    void aResultWithoutKnockoutIsLeftToTheClassicRule() throws Exception {
        Match2dKnockout.Verdict verdict = Match2dKnockout.decide(
                result("{\"score\":[1,1],\"goals\":[{\"minute\":1,\"team\":0},{\"minute\":2,\"team\":1}]}"),
                0, 0, null, TEAM1, TEAM2);

        assertThat(verdict.usable()).isFalse();
        assertThat(verdict.problem()).contains("no knockout");
    }

    @Test
    void aWinnerThatDoesNotFollowFromTheScoresIsRefused() throws Exception {
        Match2dKnockout.Verdict verdict = Match2dKnockout.decide(result("""
                {"score":[2,1],"goals":[{"minute":10,"team":0},{"minute":20,"team":1},{"minute":80,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[2,1],"extraTime":false,"winner":1,"decidedBy":"regular"}}"""),
                0, 0, null, TEAM1, TEAM2);

        assertThat(verdict.usable()).isFalse();
        assertThat(verdict.problem()).contains("winner");
    }

    @Test
    void anAggregateOtherThanTheGameExpectsIsRefused() throws Exception {
        Match2dKnockout.Verdict verdict = Match2dKnockout.decide(result("""
                {"score":[1,0],"goals":[{"minute":30,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,0],"extraTime":false,"winner":0,"decidedBy":"regular"}}"""),
                2, 7L, new int[]{2, 0}, TEAM1, TEAM2);

        assertThat(verdict.usable()).isFalse();
        assertThat(verdict.problem()).contains("aggregate");
    }

    @Test
    void incoherentExtraTimeOrShootoutIsRefused() throws Exception {
        // Level after 90' but no extra time.
        assertThat(Match2dKnockout.decide(result("""
                {"score":[0,0],"goals":[],
                 "knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":false,"winner":0,"decidedBy":"regular"}}"""),
                0, 0, null, TEAM1, TEAM2).usable()).isFalse();
        // Level after extra time but a drawn shootout.
        assertThat(Match2dKnockout.decide(result("""
                {"score":[0,0],"goals":[],
                 "knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":true,"extraTimeGoals":[0,0],
                             "penalties":[3,3],"winner":0,"decidedBy":"penalties"}}"""),
                0, 0, null, TEAM1, TEAM2).usable()).isFalse();
        // score90 that does not match the regular-time goals.
        assertThat(Match2dKnockout.decide(result("""
                {"score":[1,0],"goals":[{"minute":30,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[2,0],"extraTime":false,"winner":0,"decidedBy":"regular"}}"""),
                0, 0, null, TEAM1, TEAM2).usable()).isFalse();
        // A first leg never decides a tie.
        assertThat(Match2dKnockout.decide(result("""
                {"score":[1,0],"goals":[{"minute":30,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,0],"extraTime":false,"winner":0,"decidedBy":"regular"}}"""),
                1, 7L, null, TEAM1, TEAM2).usable()).isFalse();
    }
}
