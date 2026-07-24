package com.footballmanagergamesimulator.compartment.match;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CanonicalMatchEvaluationAdapterTest {
    private final CompartmentEngineConfig compartmentConfig = loadConfig();
    private final MatchEngineConfig matchConfig = new MatchEngineConfig();
    private final CanonicalMatchEvaluationAdapter adapter =
            new CanonicalMatchEvaluationAdapter(compartmentConfig, matchConfig);

    @Test
    void homeVenueAppliesHomeAdvantageExactlyOnceAndUsesOnlyTheRequiredMatchups() {
        CanonicalMatchEvaluation result = adapter.evaluate(team(1, Mentality.BALANCED),
                team(100, Mentality.ATTACKING), MatchVenue.HOME);
        var expected = new com.footballmanagergamesimulator.compartment.GoalProbabilityFormula(compartmentConfig)
                .expectedGoals(result.home().team().attack(), result.away().team().attackProtection(),
                        result.away().team().attack(), result.home().team().attackProtection(),
                        result.combinedOpenness(), true);
        assertThat(result.probability().homeMatchupShare()).isEqualTo(expected.homeMatchupShare());
        assertThat(result.probability().awayMatchupShare()).isEqualTo(expected.awayMatchupShare());
        assertThat(result.probability().homeXg()).isEqualTo(expected.homeXg());
        assertThat(result.probability().awayXg()).isEqualTo(expected.awayXg());
        assertThat(result.probability().homeGoals().probabilities())
                .containsExactly(expected.homeGoals().probabilities());
        assertThat(result.probability().awayGoals().probabilities())
                .containsExactly(expected.awayGoals().probabilities());
        assertThat(result.probability().homeXg()).isCloseTo(
                result.combinedOpenness()
                        * result.probability().homeMatchupShare()
                        * compartmentConfig.getProbability().getHomeAdvantage(), within(1e-12));
    }

    @Test
    void neutralIdenticalTeamsHaveEqualXgAndSymmetricOutcomes() {
        CanonicalMatchEvaluation result = adapter.evaluate(team(1, Mentality.BALANCED),
                team(100, Mentality.BALANCED), MatchVenue.NEUTRAL);
        assertThat(result.probability().homeXg()).isEqualTo(result.probability().awayXg());
        assertThat(result.outcome().homeWin()).isEqualTo(result.outcome().awayWin());
    }

    @Test
    void combinedOpennessIsTheArithmeticMean() {
        CanonicalMatchEvaluation result = adapter.evaluate(team(1, Mentality.BALANCED),
                team(100, Mentality.VERY_ATTACKING), MatchVenue.NEUTRAL);
        double expected = (result.home().team().openness() + result.away().team().openness()) / 2.0;
        assertThat(result.combinedOpenness()).isEqualTo(expected);
    }

    @Test
    void outcomeProbabilityComesFromCappedPmfAndSumsToOne() {
        CanonicalMatchEvaluation result = adapter.evaluate(team(1, Mentality.DEFENSIVE),
                team(100, Mentality.ATTACKING), MatchVenue.NEUTRAL);
        double homeWin = 0.0;
        double draw = 0.0;
        double awayWin = 0.0;
        double[] home = result.probability().homeGoals().probabilities();
        double[] away = result.probability().awayGoals().probabilities();
        for (int i = 0; i < home.length; i++) {
            for (int j = 0; j < away.length; j++) {
                if (i > j) homeWin += home[i] * away[j];
                else if (i == j) draw += home[i] * away[j];
                else awayWin += home[i] * away[j];
            }
        }
        assertThat(result.outcome()).isEqualTo(new OutcomeProbability(homeWin, draw, awayWin));
        assertThat(result.outcome().homeWin()).isBetween(0.0, 1.0);
        assertThat(result.outcome().draw()).isBetween(0.0, 1.0);
        assertThat(result.outcome().awayWin()).isBetween(0.0, 1.0);
        assertThat(result.outcome().homeWin() + result.outcome().draw() + result.outcome().awayWin())
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void neutralSwapIsSymmetricAndLineupPermutationDoesNotMatter() {
        CanonicalRuntimeTeamInput home = team(1, Mentality.ATTACKING);
        CanonicalRuntimeTeamInput away = team(100, Mentality.BALANCED);
        CanonicalMatchEvaluation first = adapter.evaluate(home, away, MatchVenue.NEUTRAL);
        List<com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer> reversed =
                new ArrayList<>(away.lineup());
        Collections.reverse(reversed);
        Map<Long, TacticalContextInput> reversedContexts = new LinkedHashMap<>();
        away.tacticalContexts().entrySet().stream().forEach(entry -> reversedContexts.put(entry.getKey(), entry.getValue()));
        CanonicalRuntimeTeamInput permutedAway = new CanonicalRuntimeTeamInput(
                away.mentality(), reversed, reversedContexts);
        CanonicalMatchEvaluation permuted = adapter.evaluate(home, permutedAway, MatchVenue.NEUTRAL);
        assertThat(permuted.combinedOpenness()).isEqualTo(first.combinedOpenness());
        assertThat(permuted.probability().homeXg()).isEqualTo(first.probability().homeXg());
        assertThat(permuted.probability().awayXg()).isEqualTo(first.probability().awayXg());
        assertThat(permuted.outcome()).isEqualTo(first.outcome());

        CanonicalMatchEvaluation swapped = adapter.evaluate(away, home, MatchVenue.NEUTRAL);
        assertThat(swapped.probability().homeXg()).isEqualTo(first.probability().awayXg());
        assertThat(swapped.probability().awayXg()).isEqualTo(first.probability().homeXg());
        assertThat(swapped.outcome().homeWin()).isCloseTo(first.outcome().awayWin(), within(1e-15));
        assertThat(swapped.outcome().awayWin()).isCloseTo(first.outcome().homeWin(), within(1e-15));
        assertThat(swapped.outcome().draw()).isCloseTo(first.outcome().draw(), within(1e-15));
    }

    @Test
    void nullAndOverlappingInputsAreRejected() {
        CanonicalRuntimeTeamInput home = team(1, Mentality.BALANCED);
        CanonicalRuntimeTeamInput away = team(100, Mentality.BALANCED);
        assertThatThrownBy(() -> adapter.evaluate(null, away, MatchVenue.HOME))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.evaluate(home, null, MatchVenue.HOME))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.evaluate(home, away, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.evaluate(home, team(1, Mentality.BALANCED), MatchVenue.NEUTRAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique across teams");
    }

    @Test
    void outcomeRecordRejectsInvalidValuesAndIsDefensive() {
        assertThatThrownBy(() -> new OutcomeProbability(Double.NaN, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutcomeProbability(0.5, 0.5, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CanonicalRuntimeTeamInput team(long idOffset, Mentality mentality) {
        List<CanonicalLineupPlayer> lineup = List.of(
                player(idOffset + 1, PlayerPosition.GK, PlayerRole.GOALKEEPER),
                player(idOffset + 2, PlayerPosition.DC, PlayerRole.CENTRAL_DEFENDER),
                player(idOffset + 3, PlayerPosition.DL, PlayerRole.FULL_BACK),
                player(idOffset + 4, PlayerPosition.DR, PlayerRole.FULL_BACK),
                player(idOffset + 5, PlayerPosition.DM, PlayerRole.BALL_WINNING_MIDFIELDER),
                player(idOffset + 6, PlayerPosition.MC, PlayerRole.CENTRAL_MIDFIELDER),
                player(idOffset + 7, PlayerPosition.ML, PlayerRole.WIDE_MIDFIELDER),
                player(idOffset + 8, PlayerPosition.MR, PlayerRole.WINGER),
                player(idOffset + 9, PlayerPosition.AMC, PlayerRole.ADVANCED_PLAYMAKER),
                player(idOffset + 10, PlayerPosition.AML, PlayerRole.INSIDE_FORWARD),
                player(idOffset + 11, PlayerPosition.ST, PlayerRole.POACHER));
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        lineup.forEach(player -> contexts.put(player.playerId(), TacticalContextInput.neutral()));
        return new CanonicalRuntimeTeamInput(mentality, lineup, contexts);
    }

    private static CanonicalLineupPlayer player(long id, PlayerPosition position, PlayerRole role) {
        return new CanonicalLineupPlayer(id, position, 1, role, Duty.SUPPORT,
                attributes(15), 90, 70,
                new PlayerCapabilitySnapshot(id, position, Map.of(position, 20),
                        Map.of(new PositionRoleKey(position, role), 10), 8, 20,
                        false, false, false), 50, Set.of(), ForwardInstruction.DEFAULT);
    }

    private static Map<PlayerAttribute, Integer> attributes(int value) {
        EnumMap<PlayerAttribute, Integer> result = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) result.put(attribute, value);
        return result;
    }

    private static CompartmentEngineConfig loadConfig() {
        try {
            var properties = new org.springframework.core.env.MutablePropertySources();
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
}
