package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerCapabilityResolverTest {
    private final MatchEngineConfig config = new MatchEngineConfig();
    private final PlayerCapabilityResolver resolver = new PlayerCapabilityResolver(config);

    @Test
    void persistentPositionWinsAndPrimaryPositionIsUsedBeforeLegacyMatrix() {
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(1L, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, 17), Map.of(), 8, 20, false, true, true);

        assertThat(resolver.positionFamiliarityOrFallback(snapshot, PlayerPosition.ST)).isEqualTo(17);
        assertThat(resolver.positionFamiliarityOrFallback(snapshot, PlayerPosition.MC)).isEqualTo(12);
    }

    @Test
    void legacyFallbackClampsToOneAndTwenty() {
        config.getPlayerValue().setDefaultFamiliarityPenalty(0.0);
        assertThat(resolver.fallbackPositionFamiliarity("UNKNOWN", PlayerPosition.MC)).isEqualTo(1);
        config.getPlayerValue().setDefaultFamiliarityPenalty(2.0);
        assertThat(resolver.fallbackPositionFamiliarity("UNKNOWN", PlayerPosition.MC)).isEqualTo(20);
    }

    @Test
    void persistentRoleWinsAndMissingValidRoleUsesTen() {
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(2L, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, 20),
                Map.of(new PositionRoleKey(PlayerPosition.ST, PlayerRole.POACHER), 16),
                8, 20, false, false, false);

        assertThat(resolver.roleFamiliarityOrFallback(snapshot, PlayerPosition.ST, PlayerRole.POACHER)).isEqualTo(16);
        assertThat(resolver.roleFamiliarityOrFallback(snapshot, PlayerPosition.ST, PlayerRole.TARGET_MAN)).isEqualTo(10);
    }

    @Test
    void invalidRolePositionAndNullInputsAreRejected() {
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(3L, PlayerPosition.GK,
                Map.of(PlayerPosition.GK, 20), Map.of(), 8, 20, false, true, false);
        assertThatThrownBy(() -> resolver.roleFamiliarityOrFallback(snapshot, PlayerPosition.GK, PlayerRole.POACHER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.positionFamiliarityOrFallback(null, PlayerPosition.ST))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.fallbackPositionFamiliarity("ST", null))
                .isInstanceOf(NullPointerException.class);
    }
}
