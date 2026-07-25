package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.OutcomeProbability;
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
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        accumulator.record(observation("Defensive", false));
        accumulator.record(observation("Very Defensive", true));
        accumulator.record(observation("Balanced", false));
        CompartmentCalibrationSnapshot snapshot = accumulator.snapshot();

        assertThat(snapshot.defensiveMentality().teamSamples()).isEqualTo(4);
        assertThat(snapshot.nonDefensiveMentality().teamSamples()).isEqualTo(2);
        assertThat(snapshot.stayForwardAbsent().teamSamples()).isEqualTo(4);
        assertThat(snapshot.stayForwardPresent().teamSamples()).isEqualTo(2);
        assertThat(snapshot.defensiveMentality().meanObservedGoalsFor()).isEqualTo(1.5);
        assertThat(snapshot.defensiveMentality().meanObservedGoalsAgainst()).isEqualTo(1.5);
    }

    @Test
    void controlledTwoMatchCorpusHasExactMetrics() {
        CompartmentShadowObservation base = observation();
        CompartmentCalibrationAccumulator accumulator = new CompartmentCalibrationAccumulator();
        accumulator.record(controlled(base, 2, 1, new double[]{.5, .5, 0}, new double[]{1, 0, 0},
                1.0, .5, new OutcomeProbability(.6, .2, .2)));
        accumulator.record(controlled(base, 0, 3, new double[]{0, 0, 1}, new double[]{.25, .5, .25},
                2.0, 1.0, new OutcomeProbability(.2, .3, .5)));
        CompartmentCalibrationSnapshot snapshot = accumulator.snapshot();

        assertThat(snapshot.legacyMeanHomeGoals()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.legacyMeanAwayGoals()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.canonicalMeanHomeXg()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.canonicalMeanAwayXg()).isCloseTo(.75, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.legacyHomeWinRate()).isCloseTo(.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.canonicalHomeWinProbability()).isCloseTo(.4, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.multiclassBrierScore()).isCloseTo(.10333333333333333, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(snapshot.logarithmicLoss()).isCloseTo((-Math.log(.6) - Math.log(.5)) / 2.0,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void convolutionUpsetsAndOverflowAreExactAndInvalidRecordsAreAtomic() {
        CompartmentShadowObservation base = observation();
        CompartmentCalibrationAccumulator histogram = new CompartmentCalibrationAccumulator();
        histogram.record(controlled(base, 3, 3, new double[]{.5, .5, 0}, new double[]{1, 0, 0},
                1, 1, new OutcomeProbability(.5, .2, .3)));
        histogram.record(controlled(base, 0, 0, new double[]{0, 0, 1}, new double[]{.25, .5, .25},
                1, 1, new OutcomeProbability(.5, .2, .3)));
        CompartmentCalibrationSnapshot histogramSnapshot = histogram.snapshot();
        assertThat(histogramSnapshot.legacyTotalGoalsHistogram().get(0)).isEqualTo(1L);
        assertThat(histogramSnapshot.legacyTotalGoalsHistogram().get(4)).isEqualTo(1L);
        assertThat(histogramSnapshot.canonicalExpectedTotalGoalsHistogram().get(0)).isCloseTo(.5,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(histogramSnapshot.canonicalExpectedTotalGoalsHistogram().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));

        CompartmentCalibrationAccumulator outcomes = new CompartmentCalibrationAccumulator();
        outcomes.record(controlled(base, 1, 0, null, null, 1, 1, new OutcomeProbability(.7, .1, .2)));
        outcomes.record(controlled(base, 0, 1, null, null, 1, 1, new OutcomeProbability(.7, .1, .2)));
        outcomes.record(controlled(base, 0, 1, null, null, 1, 1, new OutcomeProbability(.2, .1, .7)));
        outcomes.record(controlled(base, 1, 0, null, null, 1, 1, new OutcomeProbability(.2, .1, .7)));
        outcomes.record(controlled(base, 1, 0, null, null, 1, 1, new OutcomeProbability(.4, .2, .4)));
        outcomes.record(controlled(base, 0, 0, null, null, 1, 1, new OutcomeProbability(.7, .1, .2)));
        CompartmentCalibrationSnapshot outcomeSnapshot = outcomes.snapshot();
        assertThat(outcomeSnapshot.favoriteDecidedMatches()).isEqualTo(4);
        assertThat(outcomeSnapshot.observedUpsets()).isEqualTo(2);
        assertThat(outcomeSnapshot.observedUpsetRate()).isCloseTo(.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(outcomeSnapshot.expectedConditionalUpsetRate()).isCloseTo(2.0 / 9.0,
                org.assertj.core.data.Offset.offset(1e-12));

        CompartmentCalibrationSnapshot before = outcomes.snapshot();
        assertThatThrownBy(() -> outcomes.record(controlled(base, Integer.MAX_VALUE, 1,
                new double[]{.5, .5}, new double[]{1, 0, 0}, 1, 1, new OutcomeProbability(.5, .2, .3))))
                .isInstanceOf(RuntimeException.class);
        assertThat(outcomes.snapshot()).isEqualTo(before);
    }

    @Test
    void accumulatorIsDeterministicAndHasNoObservationHistory() {
        CompartmentShadowObservation base = observation();
        CompartmentCalibrationAccumulator first = new CompartmentCalibrationAccumulator();
        CompartmentCalibrationAccumulator second = new CompartmentCalibrationAccumulator();
        for (int i = 0; i < 20_000; i++) {
            CompartmentShadowObservation item = controlled(base, i % 3, (i + 1) % 3,
                    new double[]{.5, .5, 0}, new double[]{1, 0, 0}, 1, .5,
                    new OutcomeProbability(.5, .2, .3));
            first.record(item); second.record(item);
        }
        assertThat(first.snapshot()).isEqualTo(second.snapshot());
        assertThat(first.snapshot().sampleCount()).isEqualTo(20_000);
        assertThat(first.snapshot().legacyTotalGoalsHistogram()).hasSize(5);
        assertThat(java.util.Arrays.stream(CompartmentCalibrationAccumulator.class.getDeclaredFields())
                .noneMatch(field -> Collection.class.isAssignableFrom(field.getType()))).isTrue();
    }

    private static CompartmentShadowObservation observation() { return observation("Defensive", false); }

    private static CompartmentShadowObservation observation(String mentality, boolean stayForward) {
        CompartmentEngineConfig config = loadConfig();
        config.setShadowEnabled(true);
        CompartmentShadowEvaluationService service = new CompartmentShadowEvaluationService(config,
                new MatchEngineConfig(), factory(), new com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowTelemetry(),
                new CompartmentCalibrationAccumulator());
        CompartmentShadowObservation generated = service.evaluateSafely(new CompartmentShadowEvaluationService.ShadowEvaluationRequest(
                "calibration-fixture", 10, 20, 2, 1, true, false, true, MatchVenue.HOME,
                tactic(mentality), tactic(mentality), slots(1, stayForward), slots(100, stayForward))).orElseThrow();
        return new CompartmentShadowObservation(generated.fixtureKey(), generated.homeTeamId(), generated.awayTeamId(),
                generated.legacyHomeScore(), generated.legacyAwayScore(), generated.legacyResult(),
                generated.canonicalEvaluation(), 17);
    }

    private static CanonicalRuntimeInputFactory factory() {
        return new CanonicalRuntimeInputFactory(new CapabilityService(), new PlayerRoleService());
    }

    private static PersonalizedTactic tactic(String mentality) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality(mentality); tactic.setTempo("Standard"); tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard"); tactic.setPressing("Standard"); tactic.setWidth("Balanced");
        return tactic;
    }

    private static List<ShadowLineupSlotSource> slots(long offset, boolean stayForward) {
        List<String> positions = List.of("GK", "DC", "DL", "DR", "DM", "MC", "ML", "MR", "AMC", "AML", "ST");
        List<ShadowLineupSlotSource> result = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            long id = offset + i; Human human = new Human(); human.setId(id);
            PlayerSkills skills = new PlayerSkills(); skills.setPlayerId(id);
            FormationData formation = new FormationData(); formation.setPlayerId(id);
            if (stayForward && i == 10) formation.setInstructions(List.of("Stay Forward"));
            result.add(new ShadowLineupSlotSource(human, skills, formation, positions.get(i), 1));
        }
        return result;
    }

    private static CompartmentShadowObservation controlled(CompartmentShadowObservation base, int homeScore,
                                                           int awayScore, double[] homePmf, double[] awayPmf,
                                                           double homeXg, double awayXg, OutcomeProbability outcome) {
        var old = base.canonicalEvaluation().probability();
        double[] home = homePmf == null ? old.homeGoals().probabilities() : homePmf;
        double[] away = awayPmf == null ? old.awayGoals().probabilities() : awayPmf;
        int cap = homePmf == null ? old.homeGoals().cap() : homePmf.length - 1;
        var probability = new GoalProbabilityFormula.MatchProbability(0.5, 0.5, homeXg, awayXg,
                new GoalProbabilityFormula.GoalDistribution(homeXg, 1, cap, home, 0, cap),
                new GoalProbabilityFormula.GoalDistribution(awayXg, 1, cap, away, 0, cap));
        CanonicalMatchEvaluation evaluation = new CanonicalMatchEvaluation(base.canonicalEvaluation().home(),
                base.canonicalEvaluation().away(), MatchVenue.HOME, base.canonicalEvaluation().combinedOpenness(),
                probability, outcome);
        CompartmentShadowObservation.LegacyResult result = homeScore == awayScore
                ? CompartmentShadowObservation.LegacyResult.DRAW
                : homeScore > awayScore ? CompartmentShadowObservation.LegacyResult.HOME_WIN
                : CompartmentShadowObservation.LegacyResult.AWAY_WIN;
        return new CompartmentShadowObservation("controlled", 1, 2, homeScore, awayScore, result, evaluation, 17);
    }

    private static CompartmentEngineConfig loadConfig() {
        try {
            var sources = new org.springframework.core.env.MutablePropertySources();
            for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load("phase13-weights",
                    new org.springframework.core.io.ClassPathResource("compartment-scoring-weights-v1.yml"))) sources.addLast(source);
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
