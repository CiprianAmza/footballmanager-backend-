package com.footballmanagergamesimulator.compartment.shadow;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public final class CompartmentShadowEvaluationService {
    private static final Logger LOG = LoggerFactory.getLogger(CompartmentShadowEvaluationService.class);

    private final CompartmentEngineConfig compartmentConfig;
    private final CanonicalRuntimeInputFactory runtimeFactory;
    private final CanonicalMatchEvaluationAdapter matchAdapter;
    private final CompartmentShadowTelemetry telemetry;

    public CompartmentShadowEvaluationService(CompartmentEngineConfig compartmentConfig,
                                              MatchEngineConfig matchEngineConfig,
                                              CanonicalRuntimeInputFactory runtimeFactory,
                                              CompartmentShadowTelemetry telemetry) {
        this(compartmentConfig, runtimeFactory,
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig), telemetry);
    }

    CompartmentShadowEvaluationService(CompartmentEngineConfig compartmentConfig,
                                       CanonicalRuntimeInputFactory runtimeFactory,
                                       CanonicalMatchEvaluationAdapter matchAdapter,
                                       CompartmentShadowTelemetry telemetry) {
        this.compartmentConfig = Objects.requireNonNull(compartmentConfig, "compartmentConfig");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.matchAdapter = Objects.requireNonNull(matchAdapter, "matchAdapter");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public Optional<CompartmentShadowObservation> evaluateSafely(ShadowEvaluationRequest request) {
        if (!compartmentConfig.isShadowEnabled()) {
            telemetry.markSkipped(CompartmentShadowSkipReason.FLAG_DISABLED);
            return Optional.empty();
        }
        telemetry.markAttempted();
        CompartmentShadowSkipReason eligibility = eligibility(request);
        if (eligibility != null) {
            telemetry.markSkipped(eligibility);
            return Optional.empty();
        }
        try {
            List<com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot> homeSlots = toRuntimeSlots(request.homeSlots());
            List<com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot> awaySlots = toRuntimeSlots(request.awaySlots());
            CanonicalRuntimeTeamInput home = runtimeFactory.build(request.homeTactic(), homeSlots);
            CanonicalRuntimeTeamInput away = runtimeFactory.build(request.awayTactic(), awaySlots);
            long started = System.nanoTime();
            CanonicalMatchEvaluation evaluation = matchAdapter.evaluate(home, away, MatchVenue.HOME);
            long duration = System.nanoTime() - started;
            CompartmentShadowObservation observation = new CompartmentShadowObservation(
                    request.fixtureKey(), request.homeTeamId(), request.awayTeamId(),
                    request.legacyHomeScore(), request.legacyAwayScore(), legacyResult(request), evaluation, duration);
            telemetry.markSucceeded();
            LOG.debug("compartment shadow evaluation fixture={} homeTeam={} awayTeam={} durationNanos={}",
                    request.fixtureKey(), request.homeTeamId(), request.awayTeamId(), duration);
            return Optional.of(observation);
        } catch (RuntimeException ex) {
            telemetry.markFailed();
            LOG.warn("compartment shadow evaluation failed fixture={} reason={}",
                    request == null ? "<null>" : request.fixtureKey(), ex.getMessage());
            return Optional.empty();
        }
    }

    public CompartmentShadowTelemetrySnapshot telemetrySnapshot() {
        return telemetry.snapshot();
    }

    private CompartmentShadowSkipReason eligibility(ShadowEvaluationRequest request) {
        if (request == null) return CompartmentShadowSkipReason.EVALUATION_FAILED;
        if (!request.aiVsAi()) return CompartmentShadowSkipReason.NON_AI_MATCH;
        if (request.adminForcedScore()) return CompartmentShadowSkipReason.ADMIN_FORCED_SCORE;
        if (!request.tacticalModelEnabled()) return CompartmentShadowSkipReason.TACTICAL_MODEL_DISABLED;
        if (request.homeTactic() == null || request.awayTactic() == null) {
            return CompartmentShadowSkipReason.MISSING_CANONICAL_TACTIC;
        }
        if (request.venue() != MatchVenue.HOME) return CompartmentShadowSkipReason.UNSUPPORTED_VENUE;
        if (request.homeSlots() == null || request.awaySlots() == null
                || request.homeSlots().size() != 11 || request.awaySlots().size() != 11) {
            return CompartmentShadowSkipReason.INVALID_LINEUP_SIZE;
        }
        if (request.fixtureKey() == null || request.fixtureKey().isBlank()
                || request.homeTeamId() <= 0 || request.awayTeamId() <= 0
                || request.homeTeamId() == request.awayTeamId()
                || request.legacyHomeScore() < 0 || request.legacyAwayScore() < 0) {
            return CompartmentShadowSkipReason.MISSING_PLAYER_DATA;
        }
        if (request.homeSlots().stream().anyMatch(Objects::isNull)
                || request.awaySlots().stream().anyMatch(Objects::isNull)) {
            return CompartmentShadowSkipReason.MISSING_PLAYER_DATA;
        }
        if (request.homeSlots().stream().anyMatch(slot -> slot.player().getId() <= 0)
                || request.awaySlots().stream().anyMatch(slot -> slot.player().getId() <= 0)) {
            return CompartmentShadowSkipReason.MISSING_PLAYER_DATA;
        }
        if (hasDuplicatePlayer(request.homeSlots()) || hasDuplicatePlayer(request.awaySlots())
                || overlaps(request.homeSlots(), request.awaySlots())) {
            return CompartmentShadowSkipReason.DUPLICATE_PLAYER;
        }
        return null;
    }

    private static boolean hasDuplicatePlayer(Collection<ShadowLineupSlotSource> slots) {
        Set<Long> ids = new HashSet<>();
        for (ShadowLineupSlotSource slot : slots) {
            if (slot == null || slot.player() == null || !ids.add(slot.player().getId())) return true;
        }
        return false;
    }

    private static boolean overlaps(Collection<ShadowLineupSlotSource> home,
                                    Collection<ShadowLineupSlotSource> away) {
        Set<Long> ids = new HashSet<>();
        home.stream().filter(Objects::nonNull).map(slot -> slot.player().getId()).forEach(ids::add);
        return away.stream().filter(Objects::nonNull).map(slot -> slot.player().getId()).anyMatch(ids::contains);
    }

    private static List<com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot> toRuntimeSlots(
            List<ShadowLineupSlotSource> slots) {
        List<com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot> result = new ArrayList<>();
        for (ShadowLineupSlotSource slot : slots) result.add(slot.toRuntimeSlot());
        return result;
    }

    private static CompartmentShadowObservation.LegacyResult legacyResult(ShadowEvaluationRequest request) {
        if (request.legacyHomeScore() == request.legacyAwayScore()) return CompartmentShadowObservation.LegacyResult.DRAW;
        return request.legacyHomeScore() > request.legacyAwayScore()
                ? CompartmentShadowObservation.LegacyResult.HOME_WIN
                : CompartmentShadowObservation.LegacyResult.AWAY_WIN;
    }

    public record ShadowEvaluationRequest(
            String fixtureKey,
            long homeTeamId,
            long awayTeamId,
            int legacyHomeScore,
            int legacyAwayScore,
            boolean aiVsAi,
            boolean adminForcedScore,
            boolean tacticalModelEnabled,
            MatchVenue venue,
            PersonalizedTactic homeTactic,
            PersonalizedTactic awayTactic,
            List<ShadowLineupSlotSource> homeSlots,
            List<ShadowLineupSlotSource> awaySlots) {
        public ShadowEvaluationRequest {
            homeSlots = homeSlots == null ? null : Collections.unmodifiableList(new ArrayList<>(homeSlots));
            awaySlots = awaySlots == null ? null : Collections.unmodifiableList(new ArrayList<>(awaySlots));
        }

        public static ShadowEvaluationRequest home(String fixtureKey, long homeTeamId, long awayTeamId,
                                                   int legacyHomeScore, int legacyAwayScore,
                                                   boolean aiVsAi, boolean adminForcedScore,
                                                   boolean tacticalModelEnabled,
                                                   PersonalizedTactic homeTactic, PersonalizedTactic awayTactic,
                                                   List<ShadowLineupSlotSource> homeSlots,
                                                   List<ShadowLineupSlotSource> awaySlots) {
            return new ShadowEvaluationRequest(fixtureKey, homeTeamId, awayTeamId,
                    legacyHomeScore, legacyAwayScore, aiVsAi, adminForcedScore, tacticalModelEnabled,
                    MatchVenue.HOME, homeTactic, awayTactic, homeSlots, awaySlots);
        }
    }
}
