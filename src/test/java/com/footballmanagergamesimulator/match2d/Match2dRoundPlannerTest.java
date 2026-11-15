package com.footballmanagergamesimulator.match2d;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which fixtures are drawn, and when a drawn match is kept or the fixture stays classic. */
class Match2dRoundPlannerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private Match2dSettings settings;
    private Match2dEngineClient engine;

    private final Match2dTeam home = team(3, 31);
    private final Match2dTeam away = team(8, 81);

    private final Logger plannerLog = (Logger) LoggerFactory.getLogger(Match2dRoundPlanner.class);
    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void setUp() {
        settings = new Match2dSettings();
        engine = mock(Match2dEngineClient.class);
        logLines.start();
        plannerLog.addAppender(logLines);
    }

    @AfterEach
    void tearDown() {
        plannerLog.detachAppender(logLines);
    }

    private List<String> warnings() {
        return logLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Match2dRoundPlanner planner(Match2dSettings.Mode mode, boolean matchPlanEnabled) {
        settings.setMode(mode);
        return new Match2dRoundPlanner(settings, engine, json, matchPlanEnabled, now::get);
    }

    private Match2dRoundPlanner.Fixture fixture(long row, long seed, int homeGoals, int awayGoals) {
        return new Match2dRoundPlanner.Fixture(row, home, away, seed, null,
                Match2dTarget.of(KnockoutPlanSplit.regularOnly(homeGoals, awayGoals)));
    }

    @Test
    void offIsTheDefaultAndDrawsNoMatch() {
        assertThat(new Match2dSettings().getMode()).isEqualTo(Match2dSettings.Mode.OFF);
        assertThat(new Match2dSettings().getFallback()).isEqualTo(Match2dSettings.Fallback.CLASSIC);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.OFF, true);

        assertThat(planner.wants(true)).isFalse();
        assertThat(planner.wants(false)).isFalse();
    }

    @Test
    void humanModeDrawsOnlyTheMatchesOfAHumanClubAndAllModeDrawsEveryMatch() {
        Match2dRoundPlanner human = planner(Match2dSettings.Mode.HUMAN, true);
        assertThat(human.wants(true)).isTrue();
        assertThat(human.wants(false)).isFalse();

        Match2dRoundPlanner all = planner(Match2dSettings.Mode.ALL, true);
        assertThat(all.wants(true)).isTrue();
        assertThat(all.wants(false)).isTrue();
    }

    @Test
    void withoutTheCanonicalMatchPlanTheModeStaysOff() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, false);

        assertThat(planner.mode()).isEqualTo(Match2dSettings.Mode.OFF);
        assertThat(planner.wants(true)).isFalse();
    }

    @Test
    void oneMatchIsSentWithItsTargetAndTheReplayRequestKeepsTheTarget() throws Exception {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenReturn(played("5", 1, 0));

        Match2dRoundPlanner.Played out = planner.draw(fixture(5L, 77L, 1, 0));

        ArgumentCaptor<Match2dEngineClient.Request> sent = ArgumentCaptor.forClass(Match2dEngineClient.Request.class);
        verify(engine).resultOf(sent.capture());
        assertThat(sent.getValue().ref()).isEqualTo("5");
        assertThat(sent.getValue().minutes()).isEqualTo(90);
        assertThat(sent.getValue().seed()).isEqualTo(77L);
        assertThat(sent.getValue().target().score()).containsExactly(1, 0);
        assertThat(out).isNotNull();
        assertThat(out.matchRowId()).isEqualTo(5L);

        JsonNode replayBody = json.readTree(out.requestJson());
        assertThat(replayBody.get("seed").asLong()).isEqualTo(77L);
        assertThat(replayBody.get("minutes").asInt()).isEqualTo(90);
        assertThat(replayBody.get("home").get("id").asLong()).isEqualTo(3L);
        assertThat(replayBody.get("target").get("score").toString()).isEqualTo("[1,0]");
        assertThat(replayBody.has("ref")).isFalse();
    }

    @Test
    void aKnockoutFixtureSendsItsAggregateAndExtraTimeTarget() throws Exception {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenReturn(json.readValue("""
                {"ref":"5","score":[2,1],"goals":[{"minute":12,"team":0},{"minute":70,"team":1},{"minute":97,"team":0}],
                 "knockout":{"aggregate":[0,0],"score90":[1,1],"extraTime":true,"extraTimeGoals":[1,0],
                             "winner":0,"decidedBy":"extra_time"}}""", Match2dResult.class));

        Match2dRoundPlanner.Played out = planner.draw(new Match2dRoundPlanner.Fixture(5L, home, away, 77L,
                Match2dKnockout.request(0, 0, null), Match2dTarget.of(KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null))));

        assertThat(out).isNotNull();
        JsonNode replayBody = json.readTree(out.requestJson());
        assertThat(replayBody.get("knockout").get("aggregate").toString()).isEqualTo("[0,0]");
        assertThat(replayBody.get("target").toString()).isEqualTo("{\"score\":[1,1],\"extraTime\":[1,0]}");
    }

    @Test
    void aMatchThatMissesTheTargetOrFailsLeavesTheFixtureClassicWithoutPausingTheEngine() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenReturn(
                played("5", 2, 0),                                        // target is 1-0
                Match2dResult.failed("6", "target not met after 64 tries"),
                played("7", 3, 0, 1),                                     // lists one goal for a 3-0 score
                played("99", 1, 0));                                      // answer for another match

        assertThat(planner.draw(fixture(5L, 1L, 1, 0))).isNull();
        assertThat(planner.draw(fixture(6L, 2L, 1, 0))).isNull();
        assertThat(planner.draw(fixture(7L, 3L, 3, 0))).isNull();
        assertThat(planner.draw(fixture(8L, 4L, 1, 0))).isNull();
        assertThat(planner.isPaused()).isFalse();
    }

    @Test
    void anUnreachableServicePausesThe2dEngineForThirtySecondsAndTheSkippedMatchesAreCountedOnce() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenThrow(new Match2dEngineClient.Unreachable("down"))
                .thenReturn(played("9", 0, 0));

        assertThat(planner.draw(fixture(5L, 1L, 0, 0))).isNull();
        assertThat(planner.isPaused()).isTrue();
        assertThat(warnings()).singleElement().asString().contains("next ~30 s stay classic");
        assertThat(planner.draw(fixture(6L, 1L, 0, 0))).isNull();   // not asked while paused
        assertThat(planner.draw(fixture(7L, 1L, 0, 0))).isNull();
        assertThat(planner.draw(fixture(8L, 1L, 0, 0))).isNull();
        verify(engine, times(1)).resultOf(any());
        assertThat(warnings()).hasSize(1);                          // no line per skipped match

        now.addAndGet(29_999L);
        assertThat(planner.isPaused()).isTrue();
        now.addAndGet(1L);
        assertThat(planner.isPaused()).isFalse();

        assertThat(planner.draw(fixture(9L, 1L, 0, 0))).isNotNull();
        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1)).contains("3 match(es) stayed classic");
        assertThat(planner.draw(fixture(9L, 1L, 0, 0))).isNotNull();
        assertThat(warnings()).hasSize(2);                          // the count is reported once
    }

    @Test
    void aTimeoutOrAnErrorAnswerLeavesOnlyThatMatchClassicAndTheNextOneIsSent() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The 2D match engine did not answer: request timed out"))
                .thenReturn(played("6", 1, 0));

        assertThat(planner.draw(fixture(5L, 1L, 1, 0))).isNull();
        assertThat(planner.isPaused()).isFalse();
        assertThat(warnings()).singleElement().asString().contains("2D match 5 stays classic");
        assertThat(planner.draw(fixture(6L, 2L, 1, 0))).isNotNull();
        verify(engine, times(2)).resultOf(any());
    }

    @Test
    void withFallbackFailAStoppedServiceIsAnErrorAndTheBreakerDoesNotApply() {
        settings.setFallback(Match2dSettings.Fallback.FAIL);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenThrow(new Match2dEngineClient.Unreachable("down"));

        assertThatThrownBy(() -> planner.draw(fixture(5L, 1L, 0, 0))).isInstanceOf(IllegalStateException.class);
        assertThat(planner.isPaused()).isFalse();
    }

    @Test
    void withFallbackFailAMissedTargetIsAnError() {
        settings.setFallback(Match2dSettings.Fallback.FAIL);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.resultOf(any())).thenReturn(Match2dResult.failed("5", "target not met after 64 tries"));

        assertThatThrownBy(() -> planner.draw(fixture(5L, 1L, 1, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target not met");
    }

    /** A played result whose goal list matches the score unless {@code listedGoals} says otherwise. */
    private Match2dResult played(String ref, int homeGoals, int awayGoals) {
        return played(ref, homeGoals, awayGoals, homeGoals + awayGoals);
    }

    private Match2dResult played(String ref, int homeGoals, int awayGoals, int listedGoals) {
        StringBuilder goals = new StringBuilder();
        for (int i = 0; i < listedGoals; i++) {
            if (i > 0) goals.append(',');
            int team = i < homeGoals ? 0 : 1;
            goals.append("{\"minute\":").append(10 + i).append(",\"team\":").append(team)
                    .append(",\"playerId\":").append(team == 0 ? 31 : 81).append('}');
        }
        try {
            return json.readValue("{\"ref\":\"" + ref + "\",\"score\":[" + homeGoals + "," + awayGoals
                    + "],\"engineVersion\":\"1\",\"goals\":[" + goals + "],\"target\":{\"met\":true,\"tries\":1}}",
                    Match2dResult.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Match2dTeam team(long id, long playerId) {
        return new Match2dTeam(id, "Team " + id, "442", "red", "black", Map.of("formation", "442"),
                List.of(new Match2dTeam.Player(playerId, "P" + playerId, 9, "ST", "ST", 70, false,
                        null, null, null, null, false, Map.of("finishing", 15))));
    }
}
