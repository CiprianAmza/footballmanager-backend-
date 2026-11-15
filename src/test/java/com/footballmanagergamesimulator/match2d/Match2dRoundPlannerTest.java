package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Match2dRoundPlannerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private Match2dSettings settings;
    private Match2dEngineClient engine;

    private final Match2dTeam home = team(3, 31);
    private final Match2dTeam away = team(8, 81);

    @BeforeEach
    void setUp() {
        settings = new Match2dSettings();
        engine = mock(Match2dEngineClient.class);
    }

    private Match2dRoundPlanner planner(Match2dSettings.Mode mode, boolean matchPlanEnabled) {
        settings.setMode(mode);
        return new Match2dRoundPlanner(settings, engine, json, matchPlanEnabled, now::get);
    }

    @Test
    void offIsTheDefaultAndPutsNoMatchOnThe2dEngine() {
        assertThat(new Match2dSettings().getMode()).isEqualTo(Match2dSettings.Mode.OFF);
        assertThat(new Match2dSettings().getFallback()).isEqualTo(Match2dSettings.Fallback.CLASSIC);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.OFF, true);

        assertThat(planner.isActive()).isFalse();
        assertThat(planner.wants(true)).isFalse();
        assertThat(planner.wants(false)).isFalse();
    }

    @Test
    void humanModeTakesOnlyTheMatchesOfAHumanClubAndAllModeTakesEveryMatch() {
        Match2dRoundPlanner human = planner(Match2dSettings.Mode.HUMAN, true);
        assertThat(human.isActive()).isTrue();
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
        assertThat(planner.isActive()).isFalse();
        assertThat(planner.wants(true)).isFalse();
    }

    @Test
    void theWholeRoundGoesInOneRequestOfNinetyMinutesWithTheMatchRowAsReference() throws Exception {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.results(any())).thenReturn(List.of(played("5", 1, 0), played("6", 0, 0)));

        Map<Long, Match2dRoundPlanner.Played> out = planner.play(List.of(
                new Match2dRoundPlanner.Fixture(5L, home, away, 77L),
                new Match2dRoundPlanner.Fixture(6L, away, home, 78L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Match2dEngineClient.Request>> sent = ArgumentCaptor.forClass(List.class);
        verify(engine).results(sent.capture());
        assertThat(sent.getValue()).extracting(Match2dEngineClient.Request::ref).containsExactly("5", "6");
        assertThat(sent.getValue()).extracting(Match2dEngineClient.Request::minutes).containsOnly(90);
        assertThat(sent.getValue().get(0).seed()).isEqualTo(77L);
        assertThat(out).containsOnlyKeys(5L, 6L);
        assertThat(out.get(5L).homeGoals()).isEqualTo(1);

        JsonNode replayBody = json.readTree(out.get(5L).requestJson());
        assertThat(replayBody.get("seed").asLong()).isEqualTo(77L);
        assertThat(replayBody.get("minutes").asInt()).isEqualTo(90);
        assertThat(replayBody.get("home").get("id").asLong()).isEqualTo(3L);
        assertThat(replayBody.has("ref")).isFalse();
    }

    @Test
    void aMatchTheEngineCouldNotPlayIsLeftToTheClassicEngineAndTheOthersAreKept() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.results(any())).thenReturn(List.of(
                Match2dResult.failed("5", "no keeper"), played("6", 2, 2), played("7", 3, 0, 1)));

        Map<Long, Match2dRoundPlanner.Played> out = planner.play(List.of(
                new Match2dRoundPlanner.Fixture(5L, home, away, 1L),
                new Match2dRoundPlanner.Fixture(6L, home, away, 2L),
                new Match2dRoundPlanner.Fixture(7L, home, away, 3L)));

        assertThat(out).containsOnlyKeys(6L);   // 7 lists one goal for a 3-0 score
        assertThat(planner.isActive()).isTrue();
    }

    @Test
    void aStoppedServiceLeavesTheRoundToTheClassicEngineAndPausesThe2dEngineForThirtySeconds() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.results(any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThat(planner.play(List.of(new Match2dRoundPlanner.Fixture(5L, home, away, 1L)))).isEmpty();
        assertThat(planner.isActive()).isFalse();

        now.addAndGet(29_999L);
        assertThat(planner.isActive()).isFalse();
        now.addAndGet(1L);
        assertThat(planner.isActive()).isTrue();
    }

    @Test
    void withFallbackFailAStoppedServiceIsAnErrorAndTheBreakerDoesNotApply() {
        settings.setFallback(Match2dSettings.Fallback.FAIL);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.results(any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> planner.play(List.of(new Match2dRoundPlanner.Fixture(5L, home, away, 1L))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(planner.isActive()).isTrue();
    }

    @Test
    void withFallbackFailAFailedMatchIsAnError() {
        settings.setFallback(Match2dSettings.Fallback.FAIL);
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);
        when(engine.results(any())).thenReturn(List.of(Match2dResult.failed("5", "no keeper")));

        assertThatThrownBy(() -> planner.play(List.of(new Match2dRoundPlanner.Fixture(5L, home, away, 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no keeper");
    }

    @Test
    void noFixturesMeansNoRequest() {
        Match2dRoundPlanner planner = planner(Match2dSettings.Mode.ALL, true);

        assertThat(planner.play(List.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(engine);
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
                    + "],\"engineVersion\":\"1\",\"goals\":[" + goals + "]}", Match2dResult.class);
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
