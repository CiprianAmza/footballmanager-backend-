package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowEvaluationService;
import com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowObservation;
import com.footballmanagergamesimulator.compartment.shadow.ShadowLineupSlotSource;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import com.footballmanagergamesimulator.service.PlayerRoleService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompartmentCalibrationAccumulatorTest {
    @Test
    void recordsBoundedMetricsAndKeepsHistogramOrderAndImmutability() {
        CompartmentShadowObservation observation = observation();
        CompartmentCalibrationAccumulator accumulator = new CompartmentCalibrationAccumulator();
        accumulator.record(observation);

        CompartmentCalibrationSnapshot snapshot = accumulator.snapshot();
        assertThat(snapshot.sampleCount()).isEqualTo(1);
        assertThat(snapshot.legacyMeanHomeGoals()).isEqualTo(2.0);
        assertThat(snapshot.legacyMeanAwayGoals()).isEqualTo(1.0);
        assertThat(snapshot.multiclassBrierScore()).isGreaterThanOrEqualTo(0.0);
        assertThat(snapshot.logarithmicLoss()).isFinite().isGreaterThanOrEqualTo(0.0);
        assertThat(snapshot.meanTotalDurationNanos()).isEqualTo(17.0);
        assertThat(snapshot.maxTotalDurationNanos()).isEqualTo(17);
        assertThat(snapshot.legacyTotalGoalsHistogram().keySet()).containsExactlyElementsOf(
                List.copyOf(snapshot.legacyTotalGoalsHistogram().keySet()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.legacyTotalGoalsHistogram().put(0, 4L))
                .isInstanceOf(UnsupportedOperationException.class);

        accumulator.record(observation);
        assertThat(accumulator.snapshot().sampleCount()).isEqualTo(2);
        accumulator.reset();
        assertThat(accumulator.snapshot().sampleCount()).isZero();
        assertThat(accumulator.snapshot().legacyTotalGoalsHistogram()).isEmpty();
    }

    @Test
    void segmentMeansUseTheirOwnTeamSampleCounts() {
        CompartmentCalibrationAccumulator accumulator = new CompartmentCalibrationAccumulator();
        accumulator.record(observation());
        CompartmentCalibrationSnapshot snapshot = accumulator.snapshot();

        assertThat(snapshot.defensiveMentality().teamSamples()).isEqualTo(2);
        assertThat(snapshot.nonDefensiveMentality().teamSamples()).isZero();
        assertThat(snapshot.stayForwardAbsent().teamSamples()).isEqualTo(2);
        assertThat(snapshot.stayForwardPresent().teamSamples()).isZero();
    }

    private static CompartmentShadowObservation observation() {
        CompartmentEngineConfig config = loadConfig();
        config.setShadowEnabled(true);
        CompartmentShadowEvaluationService service = new CompartmentShadowEvaluationService(config,
                new MatchEngineConfig(), factory(), new com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowTelemetry(),
                new CompartmentCalibrationAccumulator());
        CompartmentShadowObservation generated = service.evaluateSafely(new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                "calibration-fixture", 10, 20, 2, 1, true, false, true, MatchVenue.HOME,
                tactic(), tactic(), slots(1), slots(100))).orElseThrow();
        return new CompartmentShadowObservation(generated.fixtureKey(), generated.homeTeamId(), generated.awayTeamId(),
                generated.legacyHomeScore(), generated.legacyAwayScore(), generated.legacyResult(),
                generated.canonicalEvaluation(), 17);
    }

    private static CanonicalRuntimeInputFactory factory() {
        return new CanonicalRuntimeInputFactory(new CapabilityService(), new PlayerRoleService());
    }

    private static PersonalizedTactic tactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Defensive"); tactic.setTempo("Standard"); tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard"); tactic.setPressing("Standard"); tactic.setWidth("Balanced");
        return tactic;
    }

    private static List<ShadowLineupSlotSource> slots(long offset) {
        List<String> positions = List.of("GK", "DC", "DL", "DR", "DM", "MC", "ML", "MR", "AMC", "AML", "ST");
        List<ShadowLineupSlotSource> result = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            long id = offset + i; Human human = new Human(); human.setId(id);
            PlayerSkills skills = new PlayerSkills(); skills.setPlayerId(id);
            FormationData formation = new FormationData(); formation.setPlayerId(id);
            result.add(new ShadowLineupSlotSource(human, skills, formation, positions.get(i), 1));
        }
        return result;
    }

    private static CompartmentEngineConfig loadConfig() {
        try {
            var sources = new org.springframework.core.env.MutablePropertySources();
            for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load("application",
                    new org.springframework.core.io.ClassPathResource("application.yml"))) sources.addLast(source);
            return new org.springframework.boot.context.properties.bind.Binder(
                    org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                    .bind("match.engine.compartment", org.springframework.boot.context.properties.bind.Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("compartment config is not bound"));
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static final class CapabilityService extends PlayerCapabilityService {
        private CapabilityService() { super(null, null, null, null, new MatchEngineConfig()); }
        @Override public java.util.Map<Long, PlayerCapabilitySnapshot> loadAll(java.util.Collection<Long> ids) {
            java.util.Map<Long, PlayerCapabilitySnapshot> result = new LinkedHashMap<>();
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
