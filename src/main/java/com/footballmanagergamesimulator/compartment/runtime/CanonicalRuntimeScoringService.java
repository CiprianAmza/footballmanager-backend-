package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public final class CanonicalRuntimeScoringService {
    private static final Logger LOG = LoggerFactory.getLogger(CanonicalRuntimeScoringService.class);

    private final CompartmentEngineConfig compartmentConfig;
    private final CanonicalRuntimeInputFactory runtimeFactory;
    private final CanonicalScoreSampler sampler;
    private final CanonicalMatchEvaluationAdapter matchAdapter;
    private final CompartmentRuntimeScoringTelemetry telemetry;
    private final CanonicalScoringFingerprintService fingerprintService;
    private final MatchEngineConfig matchEngineConfig;
    private final String configFingerprint;

    @Autowired
    public CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                          MatchEngineConfig matchEngineConfig,
                                          CanonicalRuntimeInputFactory runtimeFactory,
                                          CanonicalScoreSampler sampler,
                                          CompartmentRuntimeScoringTelemetry telemetry,
                                          CanonicalScoringFingerprintService fingerprintService) {
        this(compartmentConfig, runtimeFactory, matchEngineConfig, sampler,
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig), telemetry,
                fingerprintService);
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   MatchEngineConfig matchEngineConfig,
                                   CanonicalRuntimeInputFactory runtimeFactory,
                                   CanonicalScoreSampler sampler,
                                   CompartmentRuntimeScoringTelemetry telemetry) {
        this(compartmentConfig, matchEngineConfig, runtimeFactory, sampler, telemetry,
                new CanonicalScoringFingerprintService());
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   RuntimeInputBuilder runtimeBuilder,
                                   MatchEvaluator evaluator,
                                   MatchEngineConfig matchEngineConfig,
                                   CanonicalScoreSampler sampler,
                                   CompartmentRuntimeScoringTelemetry telemetry,
                                   CanonicalScoringFingerprintService fingerprintService) {
        this.compartmentConfig = Objects.requireNonNull(compartmentConfig, "compartmentConfig");
        this.runtimeFactory = null;
        this.matchAdapter = null;
        this.runtimeBuilder = Objects.requireNonNull(runtimeBuilder, "runtimeBuilder");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.matchEngineConfig = Objects.requireNonNull(matchEngineConfig, "matchEngineConfig");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.configFingerprint = fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig);
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   RuntimeInputBuilder runtimeBuilder,
                                   MatchEvaluator evaluator,
                                   CanonicalScoreSampler sampler,
                                   CompartmentRuntimeScoringTelemetry telemetry) {
        this(compartmentConfig, runtimeBuilder, evaluator, new MatchEngineConfig(), sampler, telemetry,
                new CanonicalScoringFingerprintService());
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   CanonicalRuntimeInputFactory runtimeFactory,
                                   MatchEngineConfig matchEngineConfig,
                                   CanonicalScoreSampler sampler,
                                   CanonicalMatchEvaluationAdapter matchAdapter,
                                   CompartmentRuntimeScoringTelemetry telemetry,
                                   CanonicalScoringFingerprintService fingerprintService) {
        this.compartmentConfig = Objects.requireNonNull(compartmentConfig, "compartmentConfig");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.matchAdapter = Objects.requireNonNull(matchAdapter, "matchAdapter");
        this.runtimeBuilder = runtimeFactory::build;
        this.evaluator = matchAdapter::evaluate;
        this.matchEngineConfig = Objects.requireNonNull(matchEngineConfig, "matchEngineConfig");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.configFingerprint = fingerprintService.configFingerprint(compartmentConfig, matchEngineConfig);
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   CanonicalRuntimeInputFactory runtimeFactory,
                                   CanonicalScoreSampler sampler,
                                   CanonicalMatchEvaluationAdapter matchAdapter,
                                   CompartmentRuntimeScoringTelemetry telemetry) {
        this(compartmentConfig, runtimeFactory, new MatchEngineConfig(), sampler, matchAdapter, telemetry,
                new CanonicalScoringFingerprintService());
    }

    private final RuntimeInputBuilder runtimeBuilder;
    private final MatchEvaluator evaluator;

    /**
     * Score one fixture with the authoritative Compartment V1 engine.
     *
     * <p>There is deliberately no fallback contract here. Invalid canonical input or a broken
     * configuration must fail the fixture visibly; returning an empty result used to route the
     * same match through an unrelated two-axis/scalar engine and made calibration meaningless.
     */
    public CanonicalRuntimeScore score(Supplier<RuntimeScoringRequest> requestSupplier) {
        try {
            RuntimeScoringRequest request = Objects.requireNonNull(requestSupplier, "requestSupplier").get();
            validate(request);
            CanonicalRuntimeTeamInput home = runtimeBuilder.build(request.homeTactic(), request.homeSlots());
            CanonicalRuntimeTeamInput away = runtimeBuilder.build(request.awayTactic(), request.awaySlots());
            CanonicalMatchEvaluation evaluation = evaluator.evaluate(home, away, request.venue());
            long seed = MatchPlanService.seedFor(request.fixtureKey(), request.competitionId(), request.season(),
                    request.round(), request.homeTeamId(), request.awayTeamId());
            CanonicalScoreSampler.GoalSample goals = sampler.sample(evaluation, seed);
            CanonicalRuntimeScore score = new CanonicalRuntimeScore(
                    goals.homeGoals(), goals.awayGoals(),
                    goals.homeEffectiveAttack() + goals.homeEffectiveProtection(),
                    goals.awayEffectiveAttack() + goals.awayEffectiveProtection(),
                    evaluation,
                    configFingerprint,
                    fingerprintService.inputFingerprint(request, home, away),
                    goals.homeCollectiveGoals(), goals.awayCollectiveGoals(),
                    goals.homeShooterPlayerId(), goals.awayShooterPlayerId(),
                    goals.homeShooterGoals(), goals.awayShooterGoals(),
                    goals.homeRedCardPlayerId(), goals.awayRedCardPlayerId(),
                    goals.homeEffectiveAttack(), goals.homeEffectiveProtection(),
                    goals.awayEffectiveAttack(), goals.awayEffectiveProtection(),
                    goals.homeXg(), goals.awayXg(),
                    goals.homeShooterShots(), goals.awayShooterShots(),
                    goals.homePassingPlayerId(), goals.awayPassingPlayerId(),
                    goals.homePassingGoals(), goals.awayPassingGoals(),
                    goals.homePassingOpportunities(), goals.awayPassingOpportunities(),
                    goals.homePassingControl(), goals.awayPassingControl());
            telemetry.markSucceeded();
            return score;
        } catch (RuntimeException ex) {
            telemetry.markFailed();
            LOG.error("authoritative canonical scoring failed", ex);
            throw ex;
        }
    }

    public CompartmentRuntimeScoringTelemetrySnapshot telemetrySnapshot() {
        return telemetry.snapshot();
    }

    private static void validate(RuntimeScoringRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (request.fixtureKey() == null || request.fixtureKey().isBlank()) {
            throw new IllegalArgumentException("fixtureKey must not be blank");
        }
        if (request.competitionId() <= 0 || request.homeTeamId() <= 0 || request.awayTeamId() <= 0
                || request.homeTeamId() == request.awayTeamId()) {
            throw new IllegalArgumentException("team and competition IDs must be positive and distinct");
        }
        if (request.season() < 0 || request.round() < 0) {
            throw new IllegalArgumentException("season and round must be non-negative");
        }
        if (request.venue() != MatchVenue.HOME) {
            throw new IllegalArgumentException("canonical runtime scoring supports HOME venue only");
        }
        Objects.requireNonNull(request.homeTactic(), "homeTactic");
        Objects.requireNonNull(request.awayTactic(), "awayTactic");
        if (request.homeSlots().size() != 11 || request.awaySlots().size() != 11) {
            throw new IllegalArgumentException("runtime lineups must contain exactly 11 players");
        }
    }

    public record RuntimeScoringRequest(
            String fixtureKey,
            long competitionId,
            int season,
            int round,
            long homeTeamId,
            long awayTeamId,
            MatchVenue venue,
            PersonalizedTactic homeTactic,
            PersonalizedTactic awayTactic,
            List<RuntimeLineupSlot> homeSlots,
            List<RuntimeLineupSlot> awaySlots) {
        public static RuntimeScoringRequest home(String fixtureKey, long competitionId, int season, int round,
                                                 long homeTeamId, long awayTeamId,
                                                 PersonalizedTactic homeTactic, PersonalizedTactic awayTactic,
                                                 List<RuntimeLineupSlot> homeSlots, List<RuntimeLineupSlot> awaySlots) {
            return new RuntimeScoringRequest(fixtureKey, competitionId, season, round,
                    homeTeamId, awayTeamId, MatchVenue.HOME, homeTactic, awayTactic, homeSlots, awaySlots);
        }

        public RuntimeScoringRequest {
            homeSlots = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(homeSlots, "homeSlots")));
            awaySlots = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(awaySlots, "awaySlots")));
        }
    }

    @FunctionalInterface
    interface RuntimeInputBuilder {
        CanonicalRuntimeTeamInput build(PersonalizedTactic tactic, List<RuntimeLineupSlot> slots);
    }

    @FunctionalInterface
    interface MatchEvaluator {
        CanonicalMatchEvaluation evaluate(CanonicalRuntimeTeamInput home,
                                           CanonicalRuntimeTeamInput away, MatchVenue venue);
    }
}
