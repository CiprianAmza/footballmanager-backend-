package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Decides which fixtures of a round the 2D match engine plays ({@code match2d.mode}) and asks
 * the engine for all of them in one request. Whatever the engine cannot answer is left to the
 * classic engine ({@code match2d.fallback: classic}) or fails the round ({@code fail}).
 *
 * <p>After the service fails to answer, it is not asked again for
 * {@link #BREAKER_MILLIS} (classic fallback only), so a stopped service costs one timeout per
 * half minute, not one per competition.
 */
@Component
public class Match2dRoundPlanner {

    private static final Logger log = LoggerFactory.getLogger(Match2dRoundPlanner.class);

    /** Every real fixture is a full match; the engine plays no extra time. */
    public static final int MATCH_MINUTES = 90;
    static final long BREAKER_MILLIS = 30_000L;

    private final Match2dSettings settings;
    private final Match2dEngineClient engine;
    private final ObjectMapper objectMapper;
    private final boolean matchPlanEnabled;
    private final LongSupplier clock;
    /** Epoch millis before which the service is not asked again. */
    private final AtomicLong pausedUntil = new AtomicLong();

    @Autowired
    public Match2dRoundPlanner(Match2dSettings settings, Match2dEngineClient engine,
                               ObjectMapper objectMapper, MatchEngineConfig engineConfig) {
        this(settings, engine, objectMapper, engineConfig.getMatchPlan().isEnabled(), System::currentTimeMillis);
        if (settings.getMode() != Match2dSettings.Mode.OFF && !matchPlanEnabled) {
            log.warn("match2d.mode={} is ignored: the 2D engine needs match.engine.match-plan.enabled=true;"
                    + " every match is played by the classic engine.", settings.getMode());
        } else if (settings.getMode() != Match2dSettings.Mode.OFF) {
            log.info("2D match engine on: mode={}, fallback={}, service={}", settings.getMode(),
                    settings.getFallback(), settings.getService().getUrl());
        }
    }

    Match2dRoundPlanner(Match2dSettings settings, Match2dEngineClient engine, ObjectMapper objectMapper,
                        boolean matchPlanEnabled, LongSupplier clock) {
        this.settings = settings;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.matchPlanEnabled = matchPlanEnabled;
        this.clock = clock;
    }

    /** One fixture to play: the two line-ups exactly as the game fields them, and the plan seed. */
    public record Fixture(long matchRowId, Match2dTeam home, Match2dTeam away, long seed) {
    }

    /**
     * A fixture the engine played. {@code requestJson} is the {@code POST /matches} body that
     * replays exactly this match.
     */
    public record Played(long matchRowId, Match2dTeam home, Match2dTeam away, long seed,
                         Match2dResult result, String requestJson) {
        public int homeGoals() {
            return Match2dMapper.homeGoals(result);
        }

        public int awayGoals() {
            return Match2dMapper.awayGoals(result);
        }
    }

    /** The mode in force: {@code off} whenever the canonical match plan is off. */
    public Match2dSettings.Mode mode() {
        return matchPlanEnabled ? settings.getMode() : Match2dSettings.Mode.OFF;
    }

    /** Whether this round should look for 2D fixtures at all. */
    public boolean isActive() {
        if (mode() == Match2dSettings.Mode.OFF) return false;
        return settings.getFallback() == Match2dSettings.Fallback.FAIL || !isPaused();
    }

    /** Whether the mode puts this fixture on the 2D engine. */
    public boolean wants(boolean humanMatch) {
        return switch (mode()) {
            case OFF -> false;
            case HUMAN -> humanMatch;
            case ALL -> true;
        };
    }

    boolean isPaused() {
        return clock.getAsLong() < pausedUntil.get();
    }

    /**
     * Plays every fixture in one request and returns the usable results by match row id. A
     * fixture missing from the map is played by the classic engine. With
     * {@code fallback: fail} any failure throws instead.
     */
    public Map<Long, Played> play(List<Fixture> fixtures) {
        if (fixtures == null || fixtures.isEmpty()) return Map.of();
        List<Match2dEngineClient.Request> requests = new ArrayList<>(fixtures.size());
        for (Fixture fixture : fixtures) {
            requests.add(new Match2dEngineClient.Request(fixture.home(), fixture.away(), fixture.seed(),
                    MATCH_MINUTES, Long.toString(fixture.matchRowId())));
        }
        List<Match2dResult> results;
        try {
            results = engine.results(requests);
        } catch (RuntimeException failure) {
            if (settings.getFallback() == Match2dSettings.Fallback.FAIL) {
                throw new IllegalStateException("The 2D match engine could not play the round: "
                        + failure.getMessage(), failure);
            }
            pausedUntil.set(clock.getAsLong() + BREAKER_MILLIS);
            log.warn("The 2D match engine did not answer ({}); {} match(es) are played by the classic engine"
                    + " and the 2D engine is not asked again for {} s.",
                    failure.getMessage(), fixtures.size(), BREAKER_MILLIS / 1000);
            return Map.of();
        }

        Map<Long, Played> played = new HashMap<>();
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            Match2dResult result = i < results.size() ? results.get(i) : null;
            String problem = problemWith(fixture, result);
            if (problem != null) {
                if (settings.getFallback() == Match2dSettings.Fallback.FAIL) {
                    throw new IllegalStateException("The 2D match engine could not play match "
                            + fixture.matchRowId() + ": " + problem);
                }
                log.warn("2D match {} is played by the classic engine: {}", fixture.matchRowId(), problem);
                continue;
            }
            played.put(fixture.matchRowId(), new Played(fixture.matchRowId(), fixture.home(), fixture.away(),
                    fixture.seed(), result, requestJson(fixture)));
        }
        return played;
    }

    private static String problemWith(Fixture fixture, Match2dResult result) {
        if (result == null) return "no result";
        if (result.isFailed()) return result.error();
        if (result.ref() != null && !result.ref().equals(Long.toString(fixture.matchRowId()))) {
            return "the result belongs to match " + result.ref();
        }
        if (!Match2dMapper.usable(result)) return "the goals do not add up to the score";
        return null;
    }

    private String requestJson(Fixture fixture) {
        try {
            return objectMapper.writeValueAsString(Match2dEngineClient.playBody(
                    fixture.home(), fixture.away(), fixture.seed(), MATCH_MINUTES));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("2D match " + fixture.matchRowId() + " cannot be written", unwritable);
        }
    }
}
