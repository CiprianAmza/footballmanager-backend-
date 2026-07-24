package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CanonicalPlayerContextAdapterTest {
    private final CompartmentEngineConfig compartmentConfig = AdapterTestFixture.loadConfig();
    private final MatchEngineConfig matchConfig = new MatchEngineConfig();
    private final CanonicalPlayerContextAdapter adapter =
            new CanonicalPlayerContextAdapter(compartmentConfig, matchConfig);

    @Test
    void familiarityTwentyAndTenReachTheCalculatorBreakdown() {
        CanonicalPlayerEvaluation full = adapter.evaluate(player(capability(1L, 20, 12, 8, 20)),
                TacticalContextInput.neutral());
        CanonicalPlayerEvaluation half = adapter.evaluate(player(capability(2L, 10, 12, 8, 20)),
                TacticalContextInput.neutral());

        assertThat(full.positionFamiliarityFactor()).isEqualTo(1.0);
        assertThat(half.positionFamiliarityFactor()).isEqualTo(0.5);
        assertThat(full.rating().compartments().values())
                .allSatisfy(breakdown -> assertThat(breakdown.familiarityFactor()).isEqualTo(1.0));
        assertThat(half.rating().compartments().values())
                .allSatisfy(breakdown -> assertThat(breakdown.familiarityFactor()).isEqualTo(0.5));
    }

    @Test
    void roleFamiliarityAndFeetAreExplainabilityOnly() {
        CanonicalPlayerEvaluation first = adapter.evaluate(player(
                capability(3L, 20, 5, 8, 20)), TacticalContextInput.neutral());
        CanonicalPlayerEvaluation second = adapter.evaluate(player(
                capability(4L, 20, 19, 20, 8)), TacticalContextInput.neutral());

        assertThat(first.roleFamiliarityRating()).isEqualTo(5);
        assertThat(first.leftFootRating()).isEqualTo(8);
        assertThat(first.rightFootRating()).isEqualTo(20);
        assertThat(second.roleFamiliarityRating()).isEqualTo(19);
        assertThat(first.rating()).isEqualTo(second.rating());
    }

    @Test
    void evaluationFlagsDescribePositionAndRoleFallbackForTheUsedPosition() {
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(12L, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, 20),
                Map.of(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER), 15),
                8, 20, false, false, false);
        CanonicalLineupPlayer player = new CanonicalLineupPlayer(12L, PlayerPosition.MC, 1,
                PlayerRole.CENTRAL_MIDFIELDER, Duty.SUPPORT, attributes(15), 90, 70,
                snapshot, 50, Set.of(), ForwardInstruction.DEFAULT);

        CanonicalPlayerEvaluation evaluation = adapter.evaluate(player, TacticalContextInput.neutral());

        assertThat(evaluation.positionFamiliarityRating()).isEqualTo(12);
        assertThat(evaluation.roleFamiliarityRating()).isEqualTo(10);
        assertThat(evaluation.positionFallbackUsed()).isTrue();
        assertThat(evaluation.roleFallbackUsed()).isTrue();
    }

    @Test
    void evaluationFlagsAreFalseWhenUsedPositionAndExactRoleArePersistent() {
        PlayerCapabilitySnapshot snapshot = capability(13L, 18, 16, 8, 20);
        CanonicalPlayerEvaluation evaluation = adapter.evaluate(player(snapshot), TacticalContextInput.neutral());

        assertThat(evaluation.positionFallbackUsed()).isFalse();
        assertThat(evaluation.roleFallbackUsed()).isFalse();
    }

    @Test
    void legacyPositionFallbackFlagRemainsTrueWhenLegacyPositionIsInTheMap() {
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(14L, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, 20),
                Map.of(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER), 10),
                8, 20, true, false, false);

        CanonicalPlayerEvaluation evaluation = adapter.evaluate(player(snapshot), TacticalContextInput.neutral());

        assertThat(evaluation.positionFallbackUsed()).isTrue();
        assertThat(evaluation.roleFallbackUsed()).isFalse();
    }

    @Test
    void nullRoleIsNeutralAndInvalidOrMismatchedInputIsRejected() {
        CanonicalLineupPlayer nullRole = new CanonicalLineupPlayer(5L, PlayerPosition.ST, 1,
                null, Duty.SUPPORT, attributes(15), 90, 70,
                capability(5L, 20, 10, 8, 20), 87, Set.of(), ForwardInstruction.DEFAULT);
        CanonicalPlayerEvaluation evaluation = adapter.evaluate(nullRole, TacticalContextInput.neutral());
        assertThat(evaluation.role()).isNull();
        assertThat(evaluation.roleFamiliarityRating()).isEqualTo(10);
        assertThat(evaluation.roleSuitability()).isEqualTo(50.0);
        assertThat(evaluation.roleFallbackUsed()).isTrue();
        assertThat(evaluation.rating().role()).isEmpty();

        assertThatThrownBy(() -> new CanonicalLineupPlayer(6L, PlayerPosition.GK, 1,
                PlayerRole.POACHER, Duty.SUPPORT, attributes(15), 90, 70,
                capability(6L, 20, 10, 8, 20), 50, Set.of(), ForwardInstruction.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalLineupPlayer(7L, PlayerPosition.ST, 1,
                PlayerRole.POACHER, Duty.SUPPORT, attributes(15), 90, 70,
                capability(8L, 20, 10, 8, 20), 50, Set.of(), ForwardInstruction.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceCollectionsAreCopiedAndEvaluationIsDeterministic() {
        EnumMap<PlayerAttribute, Integer> sourceAttributes = new EnumMap<>(PlayerAttribute.class);
        sourceAttributes.putAll(attributes(15));
        EnumSet<PlayerTrait> sourceTraits = EnumSet.of(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        CanonicalLineupPlayer player = new CanonicalLineupPlayer(9L, PlayerPosition.ST, 1,
                PlayerRole.POACHER, Duty.ATTACK, sourceAttributes, 90, 70,
                capability(9L, 20, 10, 8, 20), 50, sourceTraits, ForwardInstruction.STAY_FORWARD);
        CanonicalPlayerEvaluation first = adapter.evaluate(player, TacticalContextInput.neutral());
        sourceAttributes.put(PlayerAttribute.FINISHING, 20);
        sourceTraits.clear();
        CanonicalPlayerEvaluation second = adapter.evaluate(player, TacticalContextInput.neutral());

        assertThat(first).isEqualTo(second);
        assertThat(player.attributes().get(PlayerAttribute.FINISHING)).isEqualTo(15);
        assertThat(player.traits()).containsExactly(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        assertThat(first.rating().compartments().get(Compartment.ATTACK).finalScore())
                .isCloseTo(second.rating().compartments().get(Compartment.ATTACK).finalScore(), within(1e-12));
    }

    private CanonicalLineupPlayer player(PlayerCapabilitySnapshot capability) {
        return new CanonicalLineupPlayer(capability.playerId(), PlayerPosition.ST, 1,
                PlayerRole.POACHER, Duty.ATTACK, attributes(15), 90, 70,
                capability, 50, Set.of(), ForwardInstruction.DEFAULT);
    }

    private static PlayerCapabilitySnapshot capability(long id, int position, int role, int left, int right) {
        return new PlayerCapabilitySnapshot(id, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, position),
                Map.of(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER), role),
                left, right, false, false, false);
    }

    private static Map<PlayerAttribute, Integer> attributes(int value) {
        EnumMap<PlayerAttribute, Integer> result = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) result.put(attribute, value);
        return result;
    }
}
