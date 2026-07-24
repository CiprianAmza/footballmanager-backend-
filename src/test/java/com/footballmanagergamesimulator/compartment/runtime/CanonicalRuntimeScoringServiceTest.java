package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.match.OutcomeProbability;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CanonicalRuntimeScoringServiceTest {
    @Test
    void flagOffDoesNotInvokeSupplierOrAttempt() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        CanonicalRuntimeScoringService service = service(config);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThat(service.scoreSafely(() -> {
            invoked.set(true);
            return null;
        })).isEmpty();
        assertThat(invoked).isFalse();
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(0, 0, 0));
    }

    @Test
    void supplierAndInvalidInputFailOpenAndCountOnce() {
        CompartmentEngineConfig config = enabledConfig();
        CanonicalRuntimeScoringService service = service(config);
        assertThat(service.scoreSafely(() -> { throw new IllegalStateException("boom"); })).isEmpty();
        assertThat(service.scoreSafely(() -> null)).isEmpty();
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(2, 0, 2));
    }

    @Test
    void successfulEvaluationUsesValidCanonicalInputAndDeterministicBoundedScore() {
        CompartmentEngineConfig config = enabledConfig();
        CanonicalMatchEvaluation evaluation = evaluation();
        var request = CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                "fixture", 7, 2026, 3, 11, 22,
                new PersonalizedTactic(), new PersonalizedTactic(), slots(1), slots(101));
        CanonicalRuntimeScoringService service = new CanonicalRuntimeScoringService(config,
                (tactic, slots) -> canonicalTeam(slots.get(0).player().getId()),
                (home, away, venue) -> evaluation,
                new CanonicalScoreSampler(), new CompartmentRuntimeScoringTelemetry());

        var first = service.scoreSafely(() -> request).orElseThrow();
        var secondService = new CanonicalRuntimeScoringService(config,
                (tactic, slots) -> canonicalTeam(slots.get(0).player().getId()),
                (home, away, venue) -> evaluation,
                new CanonicalScoreSampler(), new CompartmentRuntimeScoringTelemetry());
        var second = secondService.scoreSafely(() -> request).orElseThrow();
        assertThat(first.homeGoals()).isBetween(0, 2);
        assertThat(first.awayGoals()).isBetween(0, 2);
        assertThat(second).isEqualTo(first);
        assertThat(first.homePower()).isEqualTo(43.0);
        assertThat(first.awayPower()).isEqualTo(34.0);
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(1, 1, 0));

        var differentFixture = CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                "different-fixture", 7, 2026, 3, 11, 22,
                new PersonalizedTactic(), new PersonalizedTactic(), slots(1), slots(101));
        var thirdService = new CanonicalRuntimeScoringService(config,
                (tactic, slots) -> canonicalTeam(slots.get(0).player().getId()),
                (home, away, venue) -> evaluation,
                new CanonicalScoreSampler(), new CompartmentRuntimeScoringTelemetry());
        var third = thirdService.scoreSafely(() -> differentFixture).orElseThrow();
        assertThat(third.homeGoals()).isBetween(0, 2);
        assertThat(third.awayGoals()).isBetween(0, 2);
    }

    @Test
    void springConstructorIsTheOnlyPublicConstructor() {
        assertThat(java.util.Arrays.stream(CanonicalRuntimeScoringService.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers())).count()).isEqualTo(1);
    }

    @Test
    void bothRolloutFlagsRemainOffByDefault() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isShadowEnabled()).isFalse();
    }

    @Test
    void samplerIsSpringBeanAndIsInjectedIntoRuntimeServiceWithoutDatabase() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Wiring.class)) {
            CanonicalScoreSampler sampler = context.getBean(CanonicalScoreSampler.class);
            CanonicalRuntimeScoringService service = context.getBean(CanonicalRuntimeScoringService.class);
            var field = CanonicalRuntimeScoringService.class.getDeclaredField("sampler");
            field.setAccessible(true);
            assertThat(sampler).isSameAs(field.get(service));
        }
    }

    private static CanonicalRuntimeScoringService service(CompartmentEngineConfig config) {
        return new CanonicalRuntimeScoringService(config,
                new CanonicalRuntimeInputFactory(mock(com.footballmanagergamesimulator.service.PlayerCapabilityService.class),
                        mock(com.footballmanagergamesimulator.service.PlayerRoleService.class)),
                new CanonicalScoreSampler(), new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig()),
                new CompartmentRuntimeScoringTelemetry());
    }

    private static CompartmentEngineConfig enabledConfig() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        config.setEnabled(true);
        return config;
    }

    private static List<RuntimeLineupSlot> slots(long offset) {
        PlayerPosition[] positions = {PlayerPosition.GK, PlayerPosition.DC, PlayerPosition.DL,
                PlayerPosition.DR, PlayerPosition.DM, PlayerPosition.MC, PlayerPosition.ML,
                PlayerPosition.MR, PlayerPosition.AMC, PlayerPosition.AML, PlayerPosition.ST};
        List<RuntimeLineupSlot> result = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            long id = offset + i;
            Human player = new Human();
            player.setId(id);
            player.setFitness(90.0);
            player.setMorale(70.0);
            PlayerSkills skills = new PlayerSkills();
            skills.setPlayerId(id);
            skills.setPosition(positions[i].code());
            result.add(new RuntimeLineupSlot(player, skills, null, positions[i], 1));
        }
        return List.copyOf(result);
    }

    private static CanonicalRuntimeTeamInput canonicalTeam(long offset) {
        PlayerPosition[] positions = {PlayerPosition.GK, PlayerPosition.DC, PlayerPosition.DL,
                PlayerPosition.DR, PlayerPosition.DM, PlayerPosition.MC, PlayerPosition.ML,
                PlayerPosition.MR, PlayerPosition.AMC, PlayerPosition.AML, PlayerPosition.ST};
        List<CanonicalLineupPlayer> players = new ArrayList<>();
        java.util.Map<Long, com.footballmanagergamesimulator.compartment.TacticalContextInput> contexts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
            long id = offset + i;
            java.util.EnumMap<PlayerAttribute, Integer> attributes = new java.util.EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) attributes.put(attribute, 10);
            CanonicalLineupPlayer player = new CanonicalLineupPlayer(id, positions[i], 1, null, Duty.SUPPORT,
                    attributes, 90, 70,
                    new PlayerCapabilitySnapshot(id, positions[i], java.util.Map.of(positions[i], 20),
                            java.util.Map.of(), 8, 20, false, false, false), 50,
                    java.util.Set.of(), ForwardInstruction.DEFAULT);
            players.add(player);
            contexts.put(id, com.footballmanagergamesimulator.compartment.TacticalContextInput.neutral());
        }
        return new CanonicalRuntimeTeamInput(Mentality.BALANCED, players, contexts);
    }

    private static CanonicalMatchEvaluation evaluation() {
        GoalProbabilityFormula.GoalDistribution homeGoals =
                new GoalProbabilityFormula.GoalDistribution(1.0, 1.0, 2, new double[]{.2, .3, .5}, 0, 2);
        GoalProbabilityFormula.GoalDistribution awayGoals =
                new GoalProbabilityFormula.GoalDistribution(1.0, 1.0, 2, new double[]{.1, .2, .7}, 0, 2);
        GoalProbabilityFormula.MatchProbability probability = new GoalProbabilityFormula.MatchProbability(
                .5, .5, 1.0, 1.0, homeGoals, awayGoals);
        return new CanonicalMatchEvaluation(
                new CanonicalTeamEvaluation(List.of(), aggregation(40.0, 3.0)),
                new CanonicalTeamEvaluation(List.of(), aggregation(30.0, 4.0)),
                MatchVenue.HOME, 1.0, probability, new OutcomeProbability(.3, .4, .3));
    }

    private static TeamCompartmentAggregator.TeamAggregationResult aggregation(double attack, double protection) {
        return new TeamCompartmentAggregator.TeamAggregationResult(Mentality.BALANCED, 1.0,
                new TeamCompartmentAggregator.RawTotals(0, 0, 0),
                new TeamCompartmentAggregator.MentalityRedistribution(0, 0, null, null, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                java.util.Map.of(), null,
                new TeamCompartmentAggregator.ExposureBreakdown(0, 0, 0, 1, protection, protection),
                List.of(), attack, protection);
    }

    @Configuration
    static class Wiring {
        @Bean CompartmentEngineConfig compartmentConfig() { return new CompartmentEngineConfig(); }
        @Bean MatchEngineConfig matchConfig() { return new MatchEngineConfig(); }
        @Bean com.footballmanagergamesimulator.service.PlayerCapabilityService capabilityService() {
            return mock(com.footballmanagergamesimulator.service.PlayerCapabilityService.class);
        }
        @Bean com.footballmanagergamesimulator.service.PlayerRoleService roleService() {
            return mock(com.footballmanagergamesimulator.service.PlayerRoleService.class);
        }
        @Bean CanonicalRuntimeInputFactory runtimeFactory(
                com.footballmanagergamesimulator.service.PlayerCapabilityService capabilities,
                com.footballmanagergamesimulator.service.PlayerRoleService roles) {
            return new CanonicalRuntimeInputFactory(capabilities, roles);
        }
        @Bean CanonicalScoreSampler sampler() { return new CanonicalScoreSampler(); }
        @Bean CompartmentRuntimeScoringTelemetry telemetry() {
            return new CompartmentRuntimeScoringTelemetry();
        }
        @Bean CanonicalRuntimeScoringService scoringService(
                CompartmentEngineConfig config, MatchEngineConfig matchConfig,
                CanonicalRuntimeInputFactory factory, CanonicalScoreSampler sampler,
                CompartmentRuntimeScoringTelemetry telemetry) {
            return new CanonicalRuntimeScoringService(config, matchConfig, factory, sampler, telemetry);
        }
    }
}
