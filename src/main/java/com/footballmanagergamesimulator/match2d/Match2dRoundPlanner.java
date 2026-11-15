package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Decides which fixtures get a match drawn by the 2D match engine ({@code match2d.mode}) and
 * asks the engine for it. The score is never the 2D engine's: the classic engine decides
 * every fixture (90 minutes, extra time, shootout) and the 2D engine is asked, one match at a
 * time, for a match that ends exactly with that result ({@link Match2dTarget}). A drawn match
 * the engine cannot deliver, or that misses the target, leaves the fixture wholly classic
 * ({@code match2d.fallback: classic}) or fails the round ({@code fail}).
 *
 * <p>When the service cannot be reached at all ({@link Match2dEngineClient.Unreachable}: it is
 * stopped), it is not asked again for {@link #BREAKER_MILLIS} (classic fallback only), so a
 * stopped service costs one connect attempt per half minute, not one per match. The matches
 * skipped meanwhile are counted and reported in one log line when the pause ends. A request
 * that times out while the engine plays, an error answer or a missed target leaves only that
 * match classic, with a log line, and pauses nothing. Safe to call from the threads that
 * simulate competitions in parallel: the engine client is thread-safe and the breaker and its
 * counter are atomic.
 */
@Component
public class Match2dRoundPlanner {

    private static final Logger log = LoggerFactory.getLogger(Match2dRoundPlanner.class);

    /** Every real fixture is a full match; extra time is played only when the target has it. */
    public static final int MATCH_MINUTES = 90;
    static final long BREAKER_MILLIS = 30_000L;

    private final Match2dSettings settings;
    private final Match2dEngineClient engine;
    private final ObjectMapper objectMapper;
    private final boolean matchPlanEnabled;
    private final LongSupplier clock;
    /** Epoch millis before which the service is not asked again. */
    private final AtomicLong pausedUntil = new AtomicLong();
    /** Matches left classic, without a request, since the breaker last opened. */
    private final AtomicInteger skippedWhilePaused = new AtomicInteger();

    @Autowired
    public Match2dRoundPlanner(Match2dSettings settings, Match2dEngineClient engine,
                               ObjectMapper objectMapper, MatchEngineConfig engineConfig) {
        this(settings, engine, objectMapper, engineConfig.getMatchPlan().isEnabled(), System::currentTimeMillis);
        if (settings.getMode() != Match2dSettings.Mode.OFF && !matchPlanEnabled) {
            log.warn("match2d.mode={} is ignored: the 2D engine needs match.engine.match-plan.enabled=true;"
                    + " no match is drawn by the 2D engine.", settings.getMode());
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

    /**
     * One fixture to draw: the two line-ups exactly as the game fields them, the plan seed,
     * the result the classic engine decided ({@code target}) and, for a match that decides a
     * knockout tie (single match or second leg), the first-leg goals ({@code knockout};
     * {@code null} for a league match or a first leg).
     */
    public record Fixture(long matchRowId, Match2dTeam home, Match2dTeam away, long seed,
                          Match2dEngineClient.Knockout knockout, Match2dTarget target) {
    }

    /**
     * A fixture the engine drew, reproducing its target. {@code requestJson} is the
     * {@code POST /matches} body (with the target) that replays exactly this match.
     */
    public record Played(long matchRowId, Match2dTeam home, Match2dTeam away, long seed,
                         Match2dResult result, String requestJson) {
    }

    /** The mode in force: {@code off} whenever the canonical match plan is off. */
    public Match2dSettings.Mode mode() {
        return matchPlanEnabled ? settings.getMode() : Match2dSettings.Mode.OFF;
    }

    /** Whether the mode gives this fixture a match drawn by the 2D engine. */
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
     * Asks the engine for the match of one fixture ({@code POST /results}, one match) and
     * returns it when it is usable and reproduces the fixture's target exactly; {@code null}
     * otherwise (the fixture then stays classic), and without a request while the breaker is
     * open. With {@code fallback: fail} any failure throws instead.
     */
    public Played draw(Fixture fixture) {
        boolean fail = settings.getFallback() == Match2dSettings.Fallback.FAIL;
        if (!fail && isPaused()) {
            skippedWhilePaused.incrementAndGet();
            return null;
        }
        int skipped = skippedWhilePaused.getAndSet(0);
        if (skipped > 0) {
            log.warn("The 2D match engine was paused: {} match(es) stayed classic without being drawn.", skipped);
        }
        String ref = Long.toString(fixture.matchRowId());
        Match2dResult result;
        try {
            result = engine.resultOf(new Match2dEngineClient.Request(fixture.home(), fixture.away(),
                    fixture.seed(), MATCH_MINUTES, ref, fixture.knockout(), fixture.target()));
        } catch (Match2dEngineClient.Unreachable unreachable) {
            if (fail) {
                throw new IllegalStateException("The 2D match engine could not draw match "
                        + fixture.matchRowId() + ": " + unreachable.getMessage(), unreachable);
            }
            pausedUntil.set(clock.getAsLong() + BREAKER_MILLIS);
            log.warn("The 2D match engine cannot be reached ({}); match {} and every match of the next"
                    + " ~{} s stay classic without asking it (counted when the pause ends).",
                    unreachable.getMessage(), fixture.matchRowId(), BREAKER_MILLIS / 1000);
            return null;
        } catch (RuntimeException failure) {
            if (fail) {
                throw new IllegalStateException("The 2D match engine could not draw match "
                        + fixture.matchRowId() + ": " + failure.getMessage(), failure);
            }
            log.warn("2D match {} stays classic: the engine did not deliver it ({})",
                    fixture.matchRowId(), failure.getMessage());
            return null;
        }
        String problem = result != null && result.ref() != null && !result.ref().equals(ref)
                ? "the result belongs to match " + result.ref()
                : fixture.target().problemWith(result);
        if (problem != null) {
            if (fail) {
                throw new IllegalStateException("The 2D match engine could not draw match "
                        + fixture.matchRowId() + ": " + problem);
            }
            log.warn("2D match {} stays classic: {}", fixture.matchRowId(), problem);
            return null;
        }
        return new Played(fixture.matchRowId(), fixture.home(), fixture.away(), fixture.seed(), result,
                requestJson(fixture));
    }

    private String requestJson(Fixture fixture) {
        try {
            return objectMapper.writeValueAsString(Match2dEngineClient.playBody(fixture.home(), fixture.away(),
                    fixture.seed(), MATCH_MINUTES, fixture.knockout(), fixture.target()));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("2D match " + fixture.matchRowId() + " cannot be written", unwritable);
        }
    }
}
