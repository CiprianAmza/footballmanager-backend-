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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public final class CanonicalRuntimeScoringService {
    private static final Logger LOG = LoggerFactory.getLogger(CanonicalRuntimeScoringService.class);

    private final CompartmentEngineConfig compartmentConfig;
    private final CanonicalRuntimeInputFactory runtimeFactory;
    private final CanonicalScoreSampler sampler;
    private final CanonicalMatchEvaluationAdapter matchAdapter;
    private final CompartmentRuntimeScoringTelemetry telemetry;

    public CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                          MatchEngineConfig matchEngineConfig,
                                          CanonicalRuntimeInputFactory runtimeFactory,
                                          CanonicalScoreSampler sampler,
                                          CompartmentRuntimeScoringTelemetry telemetry) {
        this(compartmentConfig, runtimeFactory, sampler,
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig), telemetry);
    }

    CanonicalRuntimeScoringService(CompartmentEngineConfig compartmentConfig,
                                   CanonicalRuntimeInputFactory runtimeFactory,
                                   CanonicalScoreSampler sampler,
                                   CanonicalMatchEvaluationAdapter matchAdapter,
                                   CompartmentRuntimeScoringTelemetry telemetry) {
        this.compartmentConfig = Objects.requireNonNull(compartmentConfig, "compartmentConfig");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.matchAdapter = Objects.requireNonNull(matchAdapter, "matchAdapter");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public Optional<CanonicalRuntimeScore> scoreSafely(Supplier<RuntimeScoringRequest> requestSupplier) {
        if (!compartmentConfig.isEnabled()) return Optional.empty();
        telemetry.markAttempted();
        try {
            RuntimeScoringRequest request = Objects.requireNonNull(requestSupplier, "requestSupplier").get();
            validate(request);
            CanonicalRuntimeTeamInput home = runtimeFactory.build(request.homeTactic(), request.homeSlots());
            CanonicalRuntimeTeamInput away = runtimeFactory.build(request.awayTactic(), request.awaySlots());
            CanonicalMatchEvaluation evaluation = matchAdapter.evaluate(home, away, request.venue());
            long seed = MatchPlanService.seedFor(request.fixtureKey(), request.competitionId(), request.season(),
                    request.round(), request.homeTeamId(), request.awayTeamId());
            CanonicalScoreSampler.GoalSample goals = sampler.sample(evaluation, seed);
            CanonicalRuntimeScore score = new CanonicalRuntimeScore(
                    goals.homeGoals(), goals.awayGoals(),
                    evaluation.home().team().attack() + evaluation.home().team().attackProtection(),
                    evaluation.away().team().attack() + evaluation.away().team().attackProtection(),
                    evaluation);
            telemetry.markSucceeded();
            return Optional.of(score);
        } catch (RuntimeException ex) {
            telemetry.markFailed();
            LOG.warn("canonical runtime scoring failed reason={}", ex.getMessage());
            return Optional.empty();
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
}
