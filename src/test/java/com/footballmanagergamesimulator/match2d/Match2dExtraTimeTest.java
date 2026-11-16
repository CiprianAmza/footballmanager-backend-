package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.GoalSlot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Extra-time goals of a 2D knockout match: usability, regular-time score, goal slots, request. */
class Match2dExtraTimeTest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;
    private static final Set<Long> HOME_XI = Set.of(101L, 102L);
    private static final Set<Long> AWAY_XI = Set.of(201L, 202L);

    private final ObjectMapper json = new ObjectMapper();

    private Match2dResult result(String body) throws Exception {
        return json.readValue(body, Match2dResult.class);
    }

    private static final String EXTRA_TIME_RESULT = """
            {"ref":"5","score":[2,2],"engineVersion":"1","goals":[
              {"minute":12,"team":0,"playerId":101,"assistId":102},
              {"minute":70,"team":1,"playerId":201},
              {"minute":97,"team":1,"playerId":202,"assistId":201,"how":"header"},
              {"minute":119,"team":0,"playerId":201,"own":true}],
             "knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,1],
                         "penalties":[5,4],"winner":0,"decidedBy":"penalties"}}""";

    @Test
    void goalsAfterMinuteNinetyAreUsableOnlyInAMatchThatWentToExtraTime() throws Exception {
        assertThat(Match2dMapper.usable(result(EXTRA_TIME_RESULT))).isTrue();
        assertThat(Match2dMapper.usable(result("""
                {"score":[1,0],"goals":[{"minute":95,"team":0}]}"""))).isFalse();
        assertThat(Match2dMapper.usable(result("""
                {"score":[1,0],"goals":[{"minute":95,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":false}}"""))).isFalse();
        assertThat(Match2dMapper.usable(result("""
                {"score":[1,0],"goals":[{"minute":121,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[0,0],"extraTime":true,"extraTimeGoals":[1,0]}}"""))).isFalse();
    }

    @Test
    void theRegularTimeScoreLeavesOutTheExtraTimeGoals() throws Exception {
        Match2dResult r = result(EXTRA_TIME_RESULT);

        assertThat(Match2dMapper.homeGoals(r)).isEqualTo(1);
        assertThat(Match2dMapper.awayGoals(r)).isEqualTo(1);
        assertThat(Match2dMapper.decision("CTIM:5", 7L, r, "{}", 1.0, 1.0).homeScore90()).isEqualTo(1);
    }

    @Test
    void extraTimeGoalsBecomeResolvedExtraTimeSlotsAndStayOutOfRegularTime() throws Exception {
        Match2dResult r = result(EXTRA_TIME_RESULT);

        List<GoalSlot> regular = Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);
        List<GoalSlot> extra = Match2dMapper.extraTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI);

        assertThat(regular).extracting(GoalSlot::getMinute).containsExactly(12, 70);
        assertThat(regular).allMatch(slot -> slot.getPhase() == GoalPhase.REGULAR_TIME);
        assertThat(extra).hasSize(2);
        assertThat(extra).allMatch(slot -> slot.getPhase() == GoalPhase.EXTRA_TIME && slot.isResolved());
        assertThat(extra.get(0).getTeamId()).isEqualTo(AWAY);
        assertThat(extra.get(0).getMinute()).isEqualTo(97);
        assertThat(extra.get(0).getGoalType()).isEqualTo("HEADER");
        assertThat(extra.get(0).getScorerId()).isEqualTo(202L);
        assertThat(extra.get(0).getAssistId()).isEqualTo(201L);
        assertThat(extra.get(1).getTeamId()).isEqualTo(HOME);
        assertThat(extra.get(1).getMinute()).isEqualTo(119);
        assertThat(extra.get(1).getGoalType()).isEqualTo(Match2dMapper.OWN_GOAL);
        assertThat(extra.get(1).getScorerId()).isEqualTo(201L);
    }

    @Test
    void withoutExtraTimeThereAreNoExtraTimeSlots() throws Exception {
        Match2dResult r = result("""
                {"score":[1,0],"goals":[{"minute":90,"team":0,"playerId":101}]}""");

        assertThat(Match2dMapper.extraTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI)).isEmpty();
        assertThat(Match2dMapper.regularTimeSlots(r, HOME, AWAY, HOME_XI, AWAY_XI)).hasSize(1);
    }

    @Test
    void thePlannerSendsKnockoutAndKeepsItInTheReplayRequest() throws Exception {
        Match2dSettings settings = new Match2dSettings();
        settings.setMode(Match2dSettings.Mode.ALL);
        Match2dEngineClient engine = mock(Match2dEngineClient.class);
        Match2dRoundPlanner planner = new Match2dRoundPlanner(settings, engine, json, true, () -> 0L);
        Match2dResult league = result("{\"ref\":\"6\",\"score\":[0,0],\"goals\":[]}");
        when(engine.results(any())).thenReturn(List.of(result(EXTRA_TIME_RESULT), league));
        Match2dTeam home = team(3, 101);
        Match2dTeam away = team(8, 201);

        Map<Long, Match2dRoundPlanner.Played> out = planner.play(List.of(
                new Match2dRoundPlanner.Fixture(5L, home, away, 77L, Match2dKnockout.request(0, 0, null)),
                new Match2dRoundPlanner.Fixture(6L, home, away, 78L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Match2dEngineClient.Request>> sent = ArgumentCaptor.forClass(List.class);
        verify(engine).results(sent.capture());
        assertThat(sent.getValue().get(0).knockout().aggregate()).containsExactly(0, 0);
        assertThat(sent.getValue().get(1).knockout()).isNull();
        assertThat(out).containsOnlyKeys(5L, 6L);
        assertThat(out.get(5L).homeGoals()).isEqualTo(1);
        assertThat(json.readTree(out.get(5L).requestJson()).get("knockout").get("aggregate").toString())
                .isEqualTo("[0,0]");
        assertThat(json.readTree(out.get(6L).requestJson()).has("knockout")).isFalse();
    }

    private static Match2dTeam team(long id, long playerId) {
        return new Match2dTeam(id, "Team " + id, "442", "red", "black", Map.of("formation", "442"),
                List.of(new Match2dTeam.Player(playerId, "P" + playerId, 9, "ST", "ST", 70, false,
                        null, null, null, null, false, Map.of("finishing", 15))));
    }
}
