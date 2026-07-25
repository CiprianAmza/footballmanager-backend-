package com.footballmanagergamesimulator.compartment.shadow;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.calibration.CompartmentCalibrationAccumulator;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerFootProfileRepository;
import com.footballmanagergamesimulator.repository.PlayerPositionFamiliarityRepository;
import com.footballmanagergamesimulator.repository.PlayerRoleFamiliarityRepository;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class CompartmentShadowEvaluationServiceTest {

    @Test
    void productionConstructorIsAutowiredWithCanonicalDependencies() throws NoSuchMethodException {
        Constructor<?> production = CompartmentShadowEvaluationService.class.getConstructor(
                CompartmentEngineConfig.class, MatchEngineConfig.class,
                CanonicalRuntimeInputFactory.class, CompartmentShadowTelemetry.class,
                CompartmentCalibrationAccumulator.class);
        assertThat(production.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).isTrue();
        assertThat(java.util.Arrays.stream(CompartmentShadowEvaluationService.class.getDeclaredConstructors())
                .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                .toList()).containsExactly(production);
        assertThat(production.getParameterTypes())
                .containsExactly(CompartmentEngineConfig.class, MatchEngineConfig.class,
                        CanonicalRuntimeInputFactory.class, CompartmentShadowTelemetry.class,
                        CompartmentCalibrationAccumulator.class);
    }

    @Test
    void flagOffSkipsBeforeAnyCanonicalWork() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        CanonicalRuntimeInputFactory factory = factory(capabilities);
        CanonicalMatchEvaluationAdapter adapter = adapter(config);
        CompartmentShadowEvaluationService service = service(config, factory, adapter);

        assertThat(service.evaluateSafely(request(3, 1, true, false, true, MatchVenue.HOME))).isEmpty();
        assertThat(capabilities.loadCalls).isZero();
        assertThat(service.telemetrySnapshot().skippedByReason())
                .containsEntry(CompartmentShadowSkipReason.FLAG_DISABLED, 1L);
    }

    @Test
    void flagOffDoesNotInvokeLazySupplier() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        CompartmentShadowEvaluationService service = service(config, factory(new CountingCapabilityService()), adapter(config));
        AtomicBoolean invoked = new AtomicBoolean();

        assertThat(service.evaluateSafely(() -> {
            invoked.set(true);
            return request(1, 0, true, false, true, MatchVenue.HOME);
        })).isEmpty();

        assertThat(invoked).isFalse();
    }

    @Test
    void supplierRuntimeExceptionIsAbsorbedAndCountedAsFailure() {
        CompartmentEngineConfig config = enabledConfig();
        CompartmentShadowEvaluationService service = service(config, factory(new CountingCapabilityService()), adapter(config));

        assertThat(service.evaluateSafely(() -> {
            throw new IllegalStateException("supplier failed");
        })).isEmpty();

        assertThat(service.telemetrySnapshot().attempted()).isEqualTo(1);
        assertThat(service.telemetrySnapshot().failed()).isEqualTo(1);
    }

    @Test
    void successfulShadowEvaluationCreatesOneObservationWithoutChangingLegacyScore() {
        CompartmentEngineConfig config = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        CanonicalRuntimeInputFactory factory = factory(capabilities);
        CanonicalMatchEvaluationAdapter adapter = adapter(config);
        CompartmentShadowEvaluationService service = service(config, factory, adapter);

        var observation = service.evaluateSafely(request(3, 1, true, false, true, MatchVenue.HOME));

        assertThat(observation).isPresent();
        assertThat(observation.orElseThrow().legacyHomeScore()).isEqualTo(3);
        assertThat(observation.orElseThrow().legacyAwayScore()).isEqualTo(1);
        assertThat(observation.orElseThrow().legacyResult())
                .isEqualTo(CompartmentShadowObservation.LegacyResult.HOME_WIN);
        assertThat(service.telemetrySnapshot().attempted()).isEqualTo(1);
        assertThat(service.telemetrySnapshot().succeeded()).isEqualTo(1);
        assertThat(service.telemetrySnapshot().failed()).isZero();
        assertThat(capabilities.loadCalls).isEqualTo(2);
        assertThat(observation.orElseThrow().totalDurationNanos()).isGreaterThanOrEqualTo(0);
        assertThat(observation.orElseThrow().canonicalEvaluation().probability().homeXg()).isFinite();
        assertThat(observation.orElseThrow().canonicalEvaluation().probability().awayXg()).isFinite();
        assertThat(observation.orElseThrow().canonicalEvaluation().outcome().homeWin()
                + observation.orElseThrow().canonicalEvaluation().outcome().draw()
                + observation.orElseThrow().canonicalEvaluation().outcome().awayWin()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void successfulObservationIsDelegatedToCalibrationAccumulator() {
        CompartmentEngineConfig config = enabledConfig();
        CompartmentShadowTelemetry telemetry = new CompartmentShadowTelemetry();
        CompartmentCalibrationAccumulator accumulator = new CompartmentCalibrationAccumulator();
        CompartmentShadowEvaluationService service = new CompartmentShadowEvaluationService(config,
                factory(new CountingCapabilityService()), adapter(config), telemetry, accumulator);

        assertThat(service.evaluateSafely(request(2, 1, true, false, true, MatchVenue.HOME))).isPresent();
        assertThat(accumulator.snapshot().sampleCount()).isEqualTo(1);
    }

    @Test
    void skippedAndFailedEvaluationsNeverReachCalibrationAccumulator() {
        CompartmentCalibrationAccumulator offAccumulator = new CompartmentCalibrationAccumulator();
        CompartmentEngineConfig off = new CompartmentEngineConfig();
        CompartmentShadowEvaluationService offService = new CompartmentShadowEvaluationService(off,
                factory(new CountingCapabilityService()), adapter(off), new CompartmentShadowTelemetry(), offAccumulator);
        assertThat(offService.evaluateSafely(request(1, 0, true, false, true, MatchVenue.HOME))).isEmpty();
        assertThat(offAccumulator.snapshot().sampleCount()).isZero();

        CompartmentCalibrationAccumulator failedAccumulator = new CompartmentCalibrationAccumulator();
        CompartmentEngineConfig enabled = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        capabilities.fail = true;
        CompartmentShadowEvaluationService failedService = new CompartmentShadowEvaluationService(enabled,
                factory(capabilities), adapter(enabled), new CompartmentShadowTelemetry(), failedAccumulator);
        assertThat(failedService.evaluateSafely(request(1, 0, true, false, true, MatchVenue.HOME))).isEmpty();
        assertThat(failedAccumulator.snapshot().sampleCount()).isZero();
    }

    @Test
    void modelFailureIsAbsorbedAndCounted() {
        CompartmentEngineConfig config = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        CanonicalRuntimeInputFactory factory = factory(capabilities);
        CanonicalMatchEvaluationAdapter adapter = adapter(new CompartmentEngineConfig());
        CompartmentShadowEvaluationService service = service(config, factory, adapter);

        assertThat(service.evaluateSafely(request(0, 0, true, false, true, MatchVenue.HOME))).isEmpty();
        assertThat(service.telemetrySnapshot().failed()).isEqualTo(1);
        assertThat(service.telemetrySnapshot().succeeded()).isZero();
    }

    @Test
    void capabilityPipelineFailureIsFailOpen() {
        CompartmentEngineConfig config = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        capabilities.fail = true;
        CompartmentShadowEvaluationService service = service(config, factory(capabilities), adapter(config));

        assertThat(service.evaluateSafely(request(1, 0, true, false, true, MatchVenue.HOME))).isEmpty();
        assertThat(service.telemetrySnapshot().failed()).isEqualTo(1);
    }

    @Test
    void adminForcedScoreAndMissingTacticAreSkippedWithoutEvaluation() {
        CompartmentEngineConfig config = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        CanonicalRuntimeInputFactory factory = factory(capabilities);
        CanonicalMatchEvaluationAdapter adapter = adapter(config);
        CompartmentShadowEvaluationService service = service(config, factory, adapter);

        assertThat(service.evaluateSafely(request(1, 0, true, true, true, MatchVenue.HOME))).isEmpty();
        assertThat(service.evaluateSafely(requestWithoutTactic())).isEmpty();
        assertThat(capabilities.loadCalls).isZero();
        assertThat(service.telemetrySnapshot().skippedByReason())
                .containsEntry(CompartmentShadowSkipReason.ADMIN_FORCED_SCORE, 1L)
                .containsEntry(CompartmentShadowSkipReason.MISSING_CANONICAL_TACTIC, 1L);
    }

    @Test
    void invalidLineupsAndCrossTeamDuplicateAreSkipped() {
        CompartmentEngineConfig config = enabledConfig();
        CountingCapabilityService capabilities = new CountingCapabilityService();
        CanonicalRuntimeInputFactory factory = factory(capabilities);
        CanonicalMatchEvaluationAdapter adapter = adapter(config);
        CompartmentShadowEvaluationService service = service(config, factory, adapter);

        var tooShort = request(1, 0, true, false, true, MatchVenue.HOME);
        tooShort = new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                tooShort.fixtureKey(), tooShort.homeTeamId(), tooShort.awayTeamId(), tooShort.legacyHomeScore(),
                tooShort.legacyAwayScore(), true, false, true, MatchVenue.HOME, tooShort.homeTactic(),
                tooShort.awayTactic(), tooShort.homeSlots().subList(0, 10), tooShort.awaySlots());
        assertThat(service.evaluateSafely(tooShort)).isEmpty();

        List<ShadowLineupSlotSource> tooLong = new ArrayList<>(baseSlots(1));
        tooLong.add(tooLong.get(0));
        var twelve = new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                "fixture-12", 10, 20, 1, 0, true, false, true, MatchVenue.HOME,
                tactic(), tactic(), tooLong, baseSlots(100));
        assertThat(service.evaluateSafely(twelve)).isEmpty();

        List<ShadowLineupSlotSource> duplicate = new ArrayList<>(baseSlots(1));
        duplicate.set(1, duplicate.get(0));
        var base = request(1, 0, true, false, true, MatchVenue.HOME);
        var duplicateRequest = new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                base.fixtureKey(), base.homeTeamId(), base.awayTeamId(), base.legacyHomeScore(), base.legacyAwayScore(),
                true, false, true, MatchVenue.HOME, base.homeTactic(), base.awayTactic(), duplicate, base.awaySlots());
        assertThat(service.evaluateSafely(duplicateRequest)).isEmpty();

        var crossTeam = new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                "fixture-cross", 10, 20, 1, 0, true, false, true, MatchVenue.HOME,
                tactic(), tactic(), baseSlots(1), baseSlots(1));
        assertThat(service.evaluateSafely(crossTeam)).isEmpty();

        assertThat(service.telemetrySnapshot().skippedByReason())
                .containsEntry(CompartmentShadowSkipReason.INVALID_LINEUP_SIZE, 2L)
                .containsEntry(CompartmentShadowSkipReason.DUPLICATE_PLAYER, 2L);
    }

    @Test
    void telemetryCountersAreThreadSafeAndSnapshotIsImmutable() throws Exception {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        CompartmentShadowEvaluationService service = service(config, factory(new CountingCapabilityService()),
                adapter(config));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 100; i++) executor.submit(() -> service.evaluateSafely(request(1, 1, true, false, true, MatchVenue.HOME)));
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        var snapshot = service.telemetrySnapshot();
        assertThat(snapshot.skipped()).isEqualTo(100);
        assertThat(snapshot.skippedByReason()).containsEntry(CompartmentShadowSkipReason.FLAG_DISABLED, 100L);
        assertThat(snapshot.skippedByReason()).hasSize(CompartmentShadowSkipReason.values().length);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.skippedByReason().put(
                        CompartmentShadowSkipReason.FLAG_DISABLED, 0L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CompartmentShadowEvaluationService service(CompartmentEngineConfig config,
                                                              CanonicalRuntimeInputFactory factory,
                                                              CanonicalMatchEvaluationAdapter adapter) {
        return new CompartmentShadowEvaluationService(config, factory, adapter,
                new CompartmentShadowTelemetry(), new CompartmentCalibrationAccumulator());
    }

    private static CanonicalRuntimeInputFactory factory(CountingCapabilityService capabilities) {
        return new CanonicalRuntimeInputFactory(capabilities, new com.footballmanagergamesimulator.service.PlayerRoleService());
    }

    private static CanonicalMatchEvaluationAdapter adapter(CompartmentEngineConfig config) {
        return new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
    }

    private static CompartmentEngineConfig enabledConfig() {
        CompartmentEngineConfig config = loadConfig();
        config.setShadowEnabled(true);
        return config;
    }

    private static CompartmentEngineConfig loadConfig() {
        try {
            var properties = new org.springframework.core.env.MutablePropertySources();
            for (var source : new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("weights", new org.springframework.core.io.ClassPathResource("compartment-scoring-weights-v1.yml"))) {
                properties.addLast(source);
            }
            for (var source : new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("application", new org.springframework.core.io.ClassPathResource("application.yml"))) {
                properties.addLast(source);
            }
            return new org.springframework.boot.context.properties.bind.Binder(
                    org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment",
                            org.springframework.boot.context.properties.bind.Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("compartment config is not bound"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load application.yml", e);
        }
    }

    private static CompartmentShadowEvaluationService.ShadowEvaluationRequest request(
            int homeScore, int awayScore, boolean ai, boolean admin, boolean tactical, MatchVenue venue) {
        return new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                "fixture-1", 10, 20, homeScore, awayScore, ai, admin, tactical, venue,
                tactic(), tactic(), slots(1), slots(100));
    }

    private static CompartmentShadowEvaluationService.ShadowEvaluationRequest requestWithoutTactic() {
        var request = request(1, 0, true, false, true, MatchVenue.HOME);
        return new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                request.fixtureKey(), request.homeTeamId(), request.awayTeamId(), request.legacyHomeScore(),
                request.legacyAwayScore(), true, false, true, MatchVenue.HOME, null, request.awayTactic(),
                request.homeSlots(), request.awaySlots());
    }

    private static PersonalizedTactic tactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Balanced");
        tactic.setTempo("Standard");
        tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard");
        tactic.setPressing("Standard");
        tactic.setWidth("Balanced");
        return tactic;
    }

    private static List<ShadowLineupSlotSource> slots(long offset) {
        List<String> positions = List.of("GK", "DC", "DL", "DR", "DM", "MC", "ML", "MR", "AMC", "AML", "ST");
        List<ShadowLineupSlotSource> result = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            long id = offset + i;
            Human human = new Human();
            human.setId(id);
            PlayerSkills skills = new PlayerSkills();
            skills.setPlayerId(id);
            FormationData formation = new FormationData();
            formation.setPlayerId(id);
            result.add(new ShadowLineupSlotSource(human, skills, formation, positions.get(i), 1));
        }
        return result;
    }

    private static List<ShadowLineupSlotSource> baseSlots(long offset) {
        return slots(offset);
    }

    private static final class CountingCapabilityService extends PlayerCapabilityService {
        private int loadCalls;
        private boolean fail;

        private CountingCapabilityService() {
            super(null, null, null, null, new MatchEngineConfig());
        }

        @Override
        public java.util.Map<Long, PlayerCapabilitySnapshot> loadAll(java.util.Collection<Long> ids) {
            loadCalls++;
            if (fail) throw new IllegalStateException("capability pipeline failed");
            java.util.Map<Long, PlayerCapabilitySnapshot> result = new java.util.LinkedHashMap<>();
            for (Long id : ids) {
                int index = id >= 100 ? (int) (id - 100) : (int) (id - 1);
                PlayerPosition position = PlayerPosition.values()[index];
                result.put(id, new PlayerCapabilitySnapshot(id, position, java.util.Map.of(position, 20),
                        java.util.Map.of(), 8, 20, false, true, true));
            }
            return result;
        }
    }
}
