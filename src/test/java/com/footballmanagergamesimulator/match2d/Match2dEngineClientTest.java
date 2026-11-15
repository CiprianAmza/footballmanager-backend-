package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against a throwaway local HTTP server standing in for the engine service. */
class Match2dEngineClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) engine.stop(0);
    }

    @Test
    void postsBothTeamsWithSeedAndLengthAndReturnsTheEnginesAnswer() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        String base = startEngine(200,
                "{\"id\":\"abc\",\"viewUrl\":\"http://127.0.0.1:8790/view/abc\",\"score\":[2,1]}",
                received, method);

        JsonNode answer = new Match2dEngineClient(base + "/", json)
                .play(team(1, "Inazuma Japan"), team(2, "Xenon"), 7L, 45);

        assertThat(answer.path("viewUrl").asText()).isEqualTo("http://127.0.0.1:8790/view/abc");
        assertThat(answer.path("score").get(0).asInt()).isEqualTo(2);
        assertThat(method.get()).isEqualTo("POST");
        JsonNode sent = json.readTree(received.get());
        assertThat(sent.path("home").path("name").asText()).isEqualTo("Inazuma Japan");
        assertThat(sent.path("away").path("name").asText()).isEqualTo("Xenon");
        assertThat(sent.path("seed").asLong()).isEqualTo(7L);
        assertThat(sent.path("minutes").asInt()).isEqualTo(45);
        assertThat(sent.path("home").path("players").get(0).path("ratings").path("pace").asInt()).isEqualTo(12);
        assertThat(sent.path("home").path("tactic").path("mentality").asText()).isEqualTo("Balanced");
    }

    @Test
    void engineRefusalIsReportedWithItsReason() throws Exception {
        String base = startEngine(400, "{\"error\":\"Xenon: trebuie exact 11 jucatori\"}",
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> new Match2dEngineClient(base, json)
                .play(team(1, "Inazuma Japan"), team(2, "Xenon"), 1L, 90))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.getReason()).contains("Xenon: trebuie exact 11 jucatori");
                });
    }

    @Test
    void engineThatIsNotRunningIsReportedAsUnavailableWithHowToStartIt() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        String base = "http://127.0.0.1:" + freePort;

        assertThatThrownBy(() -> new Match2dEngineClient(base, json)
                .play(team(1, "Inazuma Japan"), team(2, "Xenon"), 1L, 90))
                .isInstanceOfSatisfying(Match2dEngineClient.Unreachable.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(failure.getReason()).contains(base).contains("java -jar match2d.jar serve");
                });
    }

    @Test
    void oneDrawnMatchIsPostedToResultsWithItsTargetAndReadBack() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String base = startEngine("/results", 200, """
                {"ref":"5","score":[1,0],"goals":[{"minute":9,"team":0}],"engineVersion":"1",
                 "target":{"met":true,"tries":4,"seed":123}}""", received, new AtomicReference<>());

        Match2dResult result = new Match2dEngineClient(base, json).resultOf(new Match2dEngineClient.Request(
                team(1, "A"), team(2, "B"), 7L, 90, "5", null,
                Match2dTarget.of(com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit.regularOnly(1, 0))));

        JsonNode sent = json.readTree(received.get());
        assertThat(sent.has("matches")).isFalse();
        assertThat(sent.path("target").toString()).isEqualTo("{\"score\":[1,0]}");
        assertThat(sent.path("ref").asText()).isEqualTo("5");
        assertThat(result.score()).containsExactly(1, 0);
        assertThat(result.target().met()).isTrue();
        assertThat(result.target().tries()).isEqualTo(4);
    }

    @Test
    void aTargetTheEngineCannotMeetComesBackAsAFailedResultEvenWithAnErrorStatus() throws Exception {
        String base = startEngine("/results", 422, "{\"ref\":\"5\",\"error\":\"target not met after 64 tries\"}",
                new AtomicReference<>(), new AtomicReference<>());

        Match2dResult result = new Match2dEngineClient(base, json).resultOf(
                new Match2dEngineClient.Request(team(1, "A"), team(2, "B"), 7L, 90, "5"));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.ref()).isEqualTo("5");
        assertThat(result.error()).isEqualTo("target not met after 64 tries");
    }

    @Test
    void aMatchThatTakesLongerThanTheTimeoutIsAFailureButNotAnUnreachableService() throws Exception {
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext("/results", exchange -> {
            try {
                Thread.sleep(2_500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        engine.start();
        Match2dSettings settings = new Match2dSettings();
        settings.getService().setUrl("http://127.0.0.1:" + engine.getAddress().getPort());
        settings.getService().setTimeoutSeconds(1);

        assertThatThrownBy(() -> new Match2dEngineClient(settings, json).resultOf(
                new Match2dEngineClient.Request(team(1, "A"), team(2, "B"), 7L, 90, "5")))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure).isNotInstanceOf(Match2dEngineClient.Unreachable.class);
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void theDefaultTimeoutIsOneMinute() {
        assertThat(new Match2dSettings().getService().getTimeoutSeconds()).isEqualTo(60);
    }

    private String startEngine(int status, String body, AtomicReference<String> received,
                               AtomicReference<String> method) throws IOException {
        return startEngine("/matches", status, body, received, method);
    }

    private String startEngine(String path, int status, String body, AtomicReference<String> received,
                               AtomicReference<String> method) throws IOException {
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext(path, exchange -> {
            method.set(exchange.getRequestMethod());
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        engine.start();
        return "http://127.0.0.1:" + engine.getAddress().getPort();
    }

    private static Match2dTeam team(long id, String name) {
        Match2dTeam.Player player = new Match2dTeam.Player(id * 10, "Player", 9, "ST", "ST", 150,
                false, null, null, null, null, false, Map.of("pace", 12));
        return new Match2dTeam(id, name, "442", "blue", "yellow",
                Map.of("formation", "442", "mentality", "Balanced"), List.of(player));
    }
}
