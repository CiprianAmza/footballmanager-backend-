package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Match2dControllerTest {

    private Match2dLineupService lineups;
    private Match2dEngineClient engine;
    private Match2dController controller;
    private final Match2dTeam home = team(3, "Shadows");
    private final Match2dTeam away = team(8, "Tik Tok");

    @BeforeEach
    void setUp() {
        lineups = mock(Match2dLineupService.class);
        engine = mock(Match2dEngineClient.class);
        controller = new Match2dController(lineups, engine, new Match2dResultService(lineups, engine),
                mock(Match2dReplayService.class));
        when(lineups.build(3L)).thenReturn(home);
        when(lineups.build(8L)).thenReturn(away);
    }

    @Test
    void previewSendsBothLineupsHomeFirstAndReturnsTheEnginesAnswer() {
        JsonNode answer = JsonNodeFactory.instance.objectNode().put("viewUrl", "http://127.0.0.1:8790/view/x");
        when(engine.play(home, away, 7L, 45)).thenReturn(answer);

        assertThat(controller.preview(3L, 8L, 7L, 45)).isSameAs(answer);
    }

    @Test
    void matchLengthIsKeptBetweenOneAndAHundredAndTwentyMinutes() {
        controller.preview(3L, 8L, 1L, 500);
        controller.preview(3L, 8L, 1L, 0);

        verify(engine).play(home, away, 1L, 120);
        verify(engine).play(home, away, 1L, 1);
    }

    @Test
    void aTeamCannotPlayItself() {
        assertThatThrownBy(() -> controller.preview(3L, 3L, 1L, 90))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(engine);
    }

    @Test
    void lineupShowsWhatTheEngineWouldReceive() {
        assertThat(controller.lineup(8L)).isSameAs(away);
        verifyNoInteractions(engine);
    }

    private static Match2dTeam team(long id, String name) {
        return new Match2dTeam(id, name, "442", "black", "grey", Map.of("formation", "442"), List.of());
    }
}
