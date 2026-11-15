package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the 2D match engine, a separate local microservice
 * ({@code java -jar match2d.jar serve} in the {@code match-engine-java} project). The engine
 * plays the match from the two line-ups and answers with a summary and the address of the
 * page that replays it.
 *
 * <p>Configure the address with {@code match2d.service.url}; the default is where the
 * service listens when started without arguments.
 */
@Component
public class Match2dEngineClient {

    /** A full 90-minute match takes about a second to play; leave room for a busy machine. */
    private static final Duration DEFAULT_PLAY_TIMEOUT = Duration.ofSeconds(60);
    /** Extra time allowed per match of a group, on top of the base timeout. */
    private static final Duration PER_MATCH_TIMEOUT = Duration.ofSeconds(1);
    /** The most matches the engine accepts in one {@code POST /results}. */
    static final int MAX_GROUP = 512;

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** Base time allowed for one request ({@code match2d.service.timeout-seconds}). */
    private final Duration playTimeout;

    public Match2dEngineClient(String baseUrl, ObjectMapper objectMapper) {
        this(baseUrl, DEFAULT_PLAY_TIMEOUT, objectMapper);
    }

    /** Address ({@code match2d.service.url}) and timeout ({@code match2d.service.timeout-seconds}) from the settings. */
    @Autowired
    public Match2dEngineClient(Match2dSettings settings, ObjectMapper objectMapper) {
        this(settings.getService().getUrl(),
                Duration.ofSeconds(Math.max(1, settings.getService().getTimeoutSeconds())), objectMapper);
    }

    private Match2dEngineClient(String baseUrl, Duration playTimeout, ObjectMapper objectMapper) {
        String url = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8791" : baseUrl;
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.objectMapper = objectMapper;
        this.playTimeout = playTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Plays the match and returns the engine's answer ({@code id}, {@code viewUrl}, {@code score}, ...). */
    public JsonNode play(Match2dTeam home, Match2dTeam away, long seed, int minutes) {
        return post("/matches", playBody(home, away, seed, minutes), playTimeout);
    }

    /**
     * Plays again a match from the exact JSON body once sent to {@code POST /matches} (see
     * {@link #playBody}). The engine is deterministic, so the same body gives the same match
     * and the same replay page.
     */
    public JsonNode replay(String playRequestJson) {
        return postJson("/matches", playRequestJson, playTimeout);
    }

    /** The body of {@code POST /matches}: both line-ups, the seed and the length. */
    public static Map<String, Object> playBody(Match2dTeam home, Match2dTeam away, long seed, int minutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("home", home);
        body.put("away", away);
        body.put("seed", seed);
        body.put("minutes", minutes);
        return body;
    }

    /**
     * Plays the match and returns only its result (score, goals, player ratings, statistics),
     * without the replay page. Same match as {@link #play} for the same teams and seed.
     * {@code ref} may be {@code null}; it comes back unchanged.
     */
    public Match2dResult result(Match2dTeam home, Match2dTeam away, long seed, int minutes, String ref) {
        JsonNode answer = post("/results", new Request(home, away, seed, minutes, ref), playTimeout);
        return toResult(answer);
    }

    /**
     * Plays a group of matches and returns their results in the order asked, one per match.
     * A match the engine could not play comes back with only {@code ref} and {@code error};
     * the rest of the group is unaffected. Groups larger than the engine accepts in one
     * request are sent in several requests.
     */
    public List<Match2dResult> results(List<Request> matches) {
        List<Match2dResult> out = new ArrayList<>(matches.size());
        for (int from = 0; from < matches.size(); from += MAX_GROUP) {
            List<Request> chunk = matches.subList(from, Math.min(matches.size(), from + MAX_GROUP));
            Duration timeout = playTimeout.plus(PER_MATCH_TIMEOUT.multipliedBy(chunk.size()));
            JsonNode results = post("/results", Map.of("matches", chunk), timeout).path("results");
            if (!results.isArray() || results.size() != chunk.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The 2D match engine answered " + (results.isArray() ? results.size() : 0)
                                + " results for " + chunk.size() + " matches.");
            }
            for (JsonNode result : results) out.add(toResult(result));
        }
        return out;
    }

    /** One match for {@link #results}: the two line-ups as the engine receives them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(Match2dTeam home, Match2dTeam away, long seed, int minutes, String ref) {
    }

    private Match2dResult toResult(JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The 2D match engine sent a result that is not a JSON object.");
        }
        try {
            return objectMapper.treeToValue(answer, Match2dResult.class);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The 2D match engine sent a result that cannot be read: " + unreadable.getMessage());
        }
    }

    private JsonNode post(String path, Object body, Duration timeout) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException unwritable) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The match could not be written for the 2D match engine: " + unwritable.getMessage());
        }
        return postJson(path, json, timeout);
    }

    private JsonNode postJson(String path, String json, Duration timeout) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException unreachable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The 2D match engine is not running at " + baseUrl
                            + ". Start it with: java -jar match2d.jar serve");
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The 2D match engine did not answer: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The 2D match was interrupted before the engine answered.");
        }

        JsonNode answer;
        try {
            answer = objectMapper.readTree(response.body());
        } catch (IOException unreadable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The 2D match engine sent an answer that is not JSON.");
        }
        if (response.statusCode() != 200) {
            String reason = answer == null ? "" : answer.path("error").asText("");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The 2D match engine refused the match"
                            + (reason.isBlank() ? " (HTTP " + response.statusCode() + ")." : ": " + reason));
        }
        return answer;
    }
}
