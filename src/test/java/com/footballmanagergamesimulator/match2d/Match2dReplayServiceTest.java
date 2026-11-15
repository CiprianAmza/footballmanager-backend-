package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Match2dReplayServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private Match2dReplayRepository repository;
    private Match2dEngineClient engine;
    private Match2dReplayService service;

    @BeforeEach
    void setUp() {
        repository = mock(Match2dReplayRepository.class);
        engine = mock(Match2dEngineClient.class);
        service = new Match2dReplayService(repository, engine);
        when(repository.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
                anyLong(), anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void aMatchNotPlayedByThe2dEngineIsNotFoundAndTheEngineIsNotAsked() {
        assertThatThrownBy(() -> service.replay(1L, 2, 3, 10L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(engine);
    }

    @Test
    void aSavedMatchIsPlayedAgainFromItsExactRequest() throws Exception {
        when(repository.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(1L, 2, 3, 10L, 20L))
                .thenReturn(Optional.of(replay("1")));
        when(engine.replay("{\"seed\":5}")).thenReturn(json.readTree(
                "{\"viewUrl\":\"http://127.0.0.1:8791/view/x\",\"score\":[2,1],\"engineVersion\":\"1\"}"));

        Map<String, Object> out = service.replay(1L, 2, 3, 10L, 20L);

        assertThat(out).containsOnlyKeys("viewUrl", "score", "engineVersion", "versionMismatch");
        assertThat(out.get("viewUrl")).isEqualTo("http://127.0.0.1:8791/view/x");
        assertThat(out.get("score").toString()).isEqualTo("[2,1]");
        assertThat(out.get("versionMismatch")).isEqualTo(false);
    }

    @Test
    void anotherEngineVersionIsReportedAndTheTeamsMayComeInEitherOrder() throws Exception {
        when(repository.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(1L, 2, 3, 10L, 20L))
                .thenReturn(Optional.of(replay("1")));
        when(engine.replay("{\"seed\":5}")).thenReturn(json.readTree(
                "{\"viewUrl\":\"v\",\"score\":[0,0],\"engineVersion\":\"2\"}"));

        Map<String, Object> out = service.replay(1L, 2, 3, 20L, 10L);

        assertThat(out.get("engineVersion")).isEqualTo("2");
        assertThat(out.get("versionMismatch")).isEqualTo(true);
    }

    @Test
    void anUnreachableEngineSurfacesItsOwnStatus() {
        when(repository.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(1L, 2, 3, 10L, 20L))
                .thenReturn(Optional.of(replay("1")));
        when(engine.replay("{\"seed\":5}"))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.replay(1L, 2, 3, 10L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void recordingSavesTheRequestSeedAndEngineVersionOfTheFixture() throws Exception {
        when(repository.findByFixtureKey("CTIM:9")).thenReturn(Optional.empty());
        Match2dResult result = json.readValue("{\"score\":[0,0],\"engineVersion\":\"1\"}", Match2dResult.class);
        Match2dTeam team = new Match2dTeam(10L, "T", "442", null, null, Map.of(), List.of());

        service.record("CTIM:9", 1L, 2, 3, 10L, 20L,
                new Match2dRoundPlanner.Played(9L, team, team, 5L, result, "{\"seed\":5}"));

        ArgumentCaptor<Match2dReplay> saved = ArgumentCaptor.forClass(Match2dReplay.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFixtureKey()).isEqualTo("CTIM:9");
        assertThat(saved.getValue().getRequestJson()).isEqualTo("{\"seed\":5}");
        assertThat(saved.getValue().getSeed()).isEqualTo(5L);
        assertThat(saved.getValue().getMinutes()).isEqualTo(90);
        assertThat(saved.getValue().getEngineVersion()).isEqualTo("1");
        assertThat(saved.getValue().getTeam1Id()).isEqualTo(10L);
    }

    @Test
    void existsLooksAtBothTeamOrders() {
        when(repository.existsByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(1L, 2, 3, 20L, 10L))
                .thenReturn(true);

        assertThat(service.exists(1L, 2, 3, 10L, 20L)).isTrue();
        assertThat(service.exists(1L, 2, 4, 10L, 20L)).isFalse();
    }

    private static Match2dReplay replay(String engineVersion) {
        return new Match2dReplay("CTIM:9", 1L, 2, 3, 10L, 20L, 5L, 90, engineVersion, "{\"seed\":5}");
    }
}
