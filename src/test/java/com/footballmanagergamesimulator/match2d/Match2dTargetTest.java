package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The target of a drawn match: built from the classic decision, and checked against the result. */
class Match2dTargetTest {

    private final ObjectMapper json = new ObjectMapper();

    private Match2dResult result(String body) throws Exception {
        return json.readValue(body, Match2dResult.class);
    }

    // ---- building the target ----

    @Test
    void aLeagueMatchTargetsOnlyTheNinetyMinuteScore() {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.regularOnly(2, 1));

        assertThat(target.score()).containsExactly(2, 1);
        assertThat(target.extraTime()).isNull();
        assertThat(target.penalties()).isNull();
    }

    @Test
    void aTieDecidedInExtraTimeTargetsTheExtraTimeGoalsWithoutAShootout() {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null));

        assertThat(target.score()).containsExactly(1, 1);
        assertThat(target.extraTime()).containsExactly(1, 0);
        assertThat(target.penalties()).isNull();
    }

    @Test
    void aTieDecidedOnPenaltiesTargetsAGoallessExtraTimeAndTheShootout() {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.knockout(0, 0, 0, 0, 4, 5));

        assertThat(target.score()).containsExactly(0, 0);
        assertThat(target.extraTime()).containsExactly(0, 0);
        assertThat(target.penalties()).containsExactly(4, 5);
    }

    @Test
    void aKnockoutMatchDecidedInNinetyMinutesHasNoExtraTimeTarget() {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.knockout(3, 1, null, null, null, null));

        assertThat(target.extraTime()).isNull();
        assertThat(target.penalties()).isNull();
    }

    @Test
    void theRequestCarriesTheTargetAndLeavesOutWhatWasNotPlayed() throws Exception {
        Match2dTeam team = new Match2dTeam(1L, "T", "442", "red", "black", Map.of(), List.of());
        JsonNode league = json.readTree(json.writeValueAsString(new Match2dEngineClient.Request(team, team, 5L, 90,
                "9", null, Match2dTarget.of(KnockoutPlanSplit.regularOnly(2, 0)))));
        JsonNode cup = json.readTree(json.writeValueAsString(new Match2dEngineClient.Request(team, team, 5L, 90,
                "9", Match2dKnockout.request(0, 0, null),
                Match2dTarget.of(KnockoutPlanSplit.knockout(1, 1, 0, 0, 3, 2)))));

        assertThat(league.get("target").toString()).isEqualTo("{\"score\":[2,0]}");
        assertThat(league.get("seed").asLong()).isEqualTo(5L);
        assertThat(league.get("ref").asText()).isEqualTo("9");
        assertThat(league.has("knockout")).isFalse();
        assertThat(cup.get("target").toString())
                .isEqualTo("{\"score\":[1,1],\"extraTime\":[0,0],\"penalties\":[3,2]}");
        assertThat(cup.get("knockout").get("aggregate").toString()).isEqualTo("[0,0]");
        assertThat(json.readTree(json.writeValueAsString(Match2dEngineClient.playBody(team, team, 5L, 90, null,
                Match2dTarget.of(KnockoutPlanSplit.regularOnly(2, 0))))).get("target").toString())
                .isEqualTo("{\"score\":[2,0]}");
    }

    // ---- checking the result ----

    @Test
    void aResultWithExactlyTheTargetScoreMatches() throws Exception {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.regularOnly(2, 1));

        assertThat(target.problemWith(result("""
                {"score":[2,1],"goals":[{"minute":5,"team":0},{"minute":50,"team":1},{"minute":88,"team":0}],
                 "target":{"met":true,"tries":3,"seed":99}}"""))).isNull();
    }

    @Test
    void anotherScoreOrAnIncoherentResultDoesNotMatch() throws Exception {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.regularOnly(2, 1));

        assertThat(target.problemWith(result("""
                {"score":[1,2],"goals":[{"minute":5,"team":0},{"minute":50,"team":1},{"minute":88,"team":1}]}""")))
                .contains("90' goals 1-2");
        assertThat(target.problemWith(result("""
                {"score":[2,1],"goals":[{"minute":5,"team":0}]}"""))).isNotNull();
        assertThat(target.problemWith(result("""
                {"score":[2,1],"goals":[{"minute":5,"team":0},{"minute":50,"team":1},{"minute":88,"team":0}],
                 "target":{"met":false}}"""))).isNotNull();
        assertThat(target.problemWith(Match2dResult.failed("9", "target not met after 64 tries")))
                .isEqualTo("target not met after 64 tries");
        assertThat(target.problemWith(null)).isNotNull();
    }

    @Test
    void extraTimeAndTheShootoutMustBeExactlyTheTargets() throws Exception {
        Match2dTarget target = Match2dTarget.of(KnockoutPlanSplit.knockout(1, 1, 1, 1, 5, 4));
        String goals = """
                "goals":[{"minute":12,"team":0},{"minute":70,"team":1},{"minute":97,"team":1},{"minute":119,"team":0}]""";

        assertThat(target.problemWith(result("{\"score\":[2,2]," + goals + """
                ,"knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,1],
                             "penalties":[5,4],"winner":0,"decidedBy":"penalties"}}"""))).isNull();
        assertThat(target.problemWith(result("{\"score\":[2,2]," + goals + """
                ,"knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,1],
                             "penalties":[4,5],"winner":1,"decidedBy":"penalties"}}"""))).contains("shootout");
        assertThat(target.problemWith(result("{\"score\":[2,2]," + goals + """
                ,"knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,1]}}""")))
                .contains("shootout");
        assertThat(target.problemWith(result("""
                {"score":[2,1],"goals":[{"minute":12,"team":0},{"minute":70,"team":1},{"minute":97,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,0],
                             "penalties":[5,4]}}"""))).contains("extra-time goals 1-0");
    }

    @Test
    void extraTimeIsPlayedExactlyWhenTheTargetHasIt() throws Exception {
        Match2dTarget noExtraTime = Match2dTarget.of(KnockoutPlanSplit.knockout(1, 0, null, null, null, null));
        Match2dTarget goallessExtraTime = Match2dTarget.of(KnockoutPlanSplit.knockout(0, 0, 0, 0, 3, 1));
        String extraTimeResult = """
                {"score":[1,0],"goals":[{"minute":30,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,0],"extraTime":true,"extraTimeGoals":[0,0]}}""";

        assertThat(noExtraTime.problemWith(result(extraTimeResult))).contains("extra time was played");
        assertThat(goallessExtraTime.problemWith(result("""
                {"score":[0,0],"goals":[],"knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":false}}""")))
                .contains("extra time was not played");
        assertThat(goallessExtraTime.problemWith(result("""
                {"score":[0,0],"goals":[],"knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":true,
                 "extraTimeGoals":[0,0],"penalties":[3,1],"winner":0}}"""))).isNull();
    }
}
