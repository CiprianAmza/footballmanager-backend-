package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalPlayerEvaluationTest {
    private final CanonicalPlayerContextAdapter adapter = new CanonicalPlayerContextAdapter(
            AdapterTestFixture.loadConfig(), new MatchEngineConfig());

    @Test
    void exactResultInvariantsRejectContradictoryState() {
        CanonicalPlayerEvaluation valid = validRoleEvaluation();
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating() - 1,
                valid.positionFamiliarityFactor(), valid.role(), valid.roleFamiliarityRating(),
                valid.roleSuitability(), valid.roleFallbackUsed(), valid.rating()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positionFamiliarityFactor");

        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(),
                valid.positionFamiliarityFactor(), valid.role(), valid.roleFamiliarityRating(),
                valid.roleSuitability(), valid.roleFallbackUsed(), rating(valid, "DC", valid.duty(), valid.role().displayName())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating position");
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(),
                valid.positionFamiliarityFactor(), valid.role(), valid.roleFamiliarityRating(),
                valid.roleSuitability(), valid.roleFallbackUsed(), rating(valid, valid.usedPosition().code(), Duty.DEFEND, valid.role().displayName())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating duty");
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(),
                valid.positionFamiliarityFactor(), valid.role(), valid.roleFamiliarityRating(),
                valid.roleSuitability(), valid.roleFallbackUsed(), rating(valid, valid.usedPosition().code(), valid.duty(), "Wrong Role")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating role");

        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(),
                valid.positionFamiliarityFactor(), PlayerRole.GOALKEEPER, valid.roleFamiliarityRating(),
                valid.roleSuitability(), valid.roleFallbackUsed(), valid.rating()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void nullRoleRequiresNeutralValuesAndFallbackFlag() {
        CanonicalPlayerEvaluation valid = adapter.evaluate(new CanonicalLineupPlayer(
                2L, PlayerPosition.ST, 1, null, Duty.SUPPORT, attributes(15), 90, 70,
                new PlayerCapabilitySnapshot(2L, PlayerPosition.ST, Map.of(PlayerPosition.ST, 20), Map.of(),
                        8, 20, false, false, false), 75, Set.of(), ForwardInstruction.DEFAULT),
                com.footballmanagergamesimulator.compartment.TacticalContextInput.neutral());

        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(), valid.positionFamiliarityFactor(),
                null, 11, valid.roleSuitability(), true, valid.rating()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roleFamiliarityRating");
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(), valid.positionFamiliarityFactor(),
                null, 10, 51.0, true, valid.rating()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roleSuitability");
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(), valid.positionFamiliarityFactor(),
                null, 10, 50.0, false, valid.rating()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roleFallbackUsed");
        assertThatThrownBy(() -> with(valid, valid.positionFamiliarityRating(), valid.positionFamiliarityFactor(),
                null, 10, 50.0, true, rating(valid, valid.usedPosition().code(), valid.duty(), "Poacher")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating role");
    }

    private CanonicalPlayerEvaluation validRoleEvaluation() {
        return adapter.evaluate(new CanonicalLineupPlayer(
                1L, PlayerPosition.ST, 1, PlayerRole.POACHER, Duty.ATTACK, attributes(15), 90, 70,
                new PlayerCapabilitySnapshot(1L, PlayerPosition.ST, Map.of(PlayerPosition.ST, 20),
                        Map.of(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER), 12),
                        8, 20, false, false, false), 50, Set.of(), ForwardInstruction.DEFAULT),
                com.footballmanagergamesimulator.compartment.TacticalContextInput.neutral());
    }

    private static CanonicalPlayerEvaluation with(CanonicalPlayerEvaluation source,
                                                   int positionRating,
                                                   double positionFactor,
                                                   PlayerRole role,
                                                   int roleRating,
                                                   double suitability,
                                                   boolean roleFallback,
                                                   ContextualPlayerRating rating) {
        return new CanonicalPlayerEvaluation(source.playerId(), source.usedPosition(), source.occurrence(), role,
                source.duty(), positionRating, positionFactor, roleRating, suitability,
                source.leftFootRating(), source.rightFootRating(), source.positionFallbackUsed(), roleFallback,
                source.footFallbackUsed(), rating);
    }

    private static ContextualPlayerRating rating(CanonicalPlayerEvaluation source,
                                                 String position, Duty duty, String role) {
        return new ContextualPlayerRating(position, role, duty, source.rating().compartments());
    }

    private static Map<PlayerAttribute, Integer> attributes(int value) {
        EnumMap<PlayerAttribute, Integer> result = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) result.put(attribute, value);
        return result;
    }
}
