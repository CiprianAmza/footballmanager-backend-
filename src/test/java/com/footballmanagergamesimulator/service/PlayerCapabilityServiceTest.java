package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilityResolver;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerFootProfile;
import com.footballmanagergamesimulator.model.PlayerPositionFamiliarity;
import com.footballmanagergamesimulator.model.PlayerRoleFamiliarity;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerFootProfileRepository;
import com.footballmanagergamesimulator.repository.PlayerPositionFamiliarityRepository;
import com.footballmanagergamesimulator.repository.PlayerRoleFamiliarityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerCapabilityServiceTest {
    private final HumanRepository humans = mock(HumanRepository.class);
    private final PlayerPositionFamiliarityRepository positions = mock(PlayerPositionFamiliarityRepository.class);
    private final PlayerRoleFamiliarityRepository roles = mock(PlayerRoleFamiliarityRepository.class);
    private final PlayerFootProfileRepository feet = mock(PlayerFootProfileRepository.class);
    private final MatchEngineConfig config = new MatchEngineConfig();
    private final PlayerCapabilityService service =
            new PlayerCapabilityService(humans, positions, roles, feet, config);

    @Test
    void persistentValuesHavePriorityAndSnapshotsAreImmutable() {
        Human human = human(7L, "ST", "Right");
        when(humans.findAllById(List.of(7L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(7L))).thenReturn(List.of(position(7L, "MC", 14, true)));
        when(roles.findAllByPlayerIdIn(List.of(7L))).thenReturn(List.of(role(7L, "MC", "MEZZALA", 13)));
        when(feet.findAllByPlayerIdIn(List.of(7L))).thenReturn(List.of(foot(7L, 11, 19)));

        PlayerCapabilitySnapshot snapshot = service.load(7L);

        assertThat(snapshot.primaryPosition()).isEqualTo(PlayerPosition.MC);
        assertThat(snapshot.positionFamiliarity()).containsEntry(PlayerPosition.MC, 14);
        assertThat(snapshot.roleFamiliarity()).containsEntry(
                new PositionRoleKey(PlayerPosition.MC, PlayerRole.MEZZALA), 13);
        assertThat(snapshot.leftFootRating()).isEqualTo(11);
        assertThat(snapshot.rightFootRating()).isEqualTo(19);
        assertThat(snapshot.positionFallbackUsed()).isFalse();
        assertThat(snapshot.footFallbackUsed()).isFalse();
        assertThatThrownBy(() -> snapshot.positionFamiliarity().put(PlayerPosition.ST, 20))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyFallbacksResolveNaturalPositionRolesAndPreferredFootWithoutPersisting() {
        Human human = human(8L, "ST", "Both");
        when(humans.findAllById(List.of(8L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(8L))).thenReturn(List.of());
        when(roles.findAllByPlayerIdIn(List.of(8L))).thenReturn(List.of());
        when(feet.findAllByPlayerIdIn(List.of(8L))).thenReturn(List.of());

        PlayerCapabilitySnapshot snapshot = service.load(8L);

        assertThat(snapshot.primaryPosition()).isEqualTo(PlayerPosition.ST);
        assertThat(snapshot.positionFamiliarity()).containsEntry(PlayerPosition.ST, 20);
        assertThat(service.fallbackPositionFamiliarity("ST", PlayerPosition.MC)).isEqualTo(12);
        assertThat(service.roleFamiliarityOrFallback(snapshot, PlayerPosition.ST, PlayerRole.POACHER))
                .isEqualTo(10);
        assertThat(snapshot.leftFootRating()).isEqualTo(16);
        assertThat(snapshot.rightFootRating()).isEqualTo(16);
        assertThat(snapshot.positionFallbackUsed()).isTrue();
        assertThat(snapshot.roleFallbackUsed()).isTrue();
        assertThat(snapshot.footFallbackUsed()).isTrue();
    }

    @Test
    void canonicalPositionFamiliarityPrefersPersistentSecondaryThenLegacyFallback() {
        Human human = human(11L, "ST", "Right");
        when(humans.findAllById(List.of(11L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(11L))).thenReturn(List.of(position(11L, "ST", 17, true)));
        when(roles.findAllByPlayerIdIn(List.of(11L))).thenReturn(List.of());
        when(feet.findAllByPlayerIdIn(List.of(11L))).thenReturn(List.of());

        PlayerCapabilitySnapshot snapshot = service.load(11L);

        assertThat(service.positionFamiliarityOrFallback(snapshot, PlayerPosition.ST)).isEqualTo(17);
        assertThat(service.positionFamiliarityOrFallback(snapshot, PlayerPosition.MC)).isEqualTo(12);
    }

    @Test
    void canonicalPositionFamiliarityUsesPersistedSecondaryAndUnknownPrimaryDeterministically() {
        Human human = human(12L, "UNKNOWN", "Right");
        when(humans.findAllById(List.of(12L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(12L))).thenReturn(List.of(
                position(12L, "MC", 14, false)));
        when(roles.findAllByPlayerIdIn(List.of(12L))).thenReturn(List.of());
        when(feet.findAllByPlayerIdIn(List.of(12L))).thenReturn(List.of());

        PlayerCapabilitySnapshot snapshot = service.load(12L);

        assertThat(service.positionFamiliarityOrFallback(snapshot, PlayerPosition.MC)).isEqualTo(14);
        assertThat(service.positionFamiliarityOrFallback(snapshot, PlayerPosition.ST)).isEqualTo(10);
    }

    @Test
    void invalidIdsAndNonPlayersAreRejected() {
        assertThatThrownBy(() -> service.load(0L)).isInstanceOf(IllegalArgumentException.class);
        when(humans.findAllById(List.of(13L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.load(13L)).isInstanceOf(IllegalArgumentException.class);
        Human manager = human(14L, "ST", "Right");
        manager.setTypeId(4L);
        when(humans.findAllById(List.of(14L))).thenReturn(List.of(manager));
        assertThatThrownBy(() -> service.load(14L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRolePositionMismatchIsRejected() {
        Human human = human(9L, "GK", "Left");
        when(humans.findAllById(List.of(9L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(9L))).thenReturn(List.of());
        when(roles.findAllByPlayerIdIn(List.of(9L))).thenReturn(List.of(role(9L, "GK", "POACHER", 10)));
        when(feet.findAllByPlayerIdIn(List.of(9L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.load(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> service.roleFamiliarityOrFallback(
                new PlayerCapabilitySnapshot(1L, null, Map.of(), Map.of(), 8, 20, true, true, true),
                PlayerPosition.GK, PlayerRole.POACHER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateLogicalKeysAndPrimaryPositionsAreDetected() {
        Human human = human(10L, "MC", "Right");
        when(humans.findAllById(List.of(10L))).thenReturn(List.of(human));
        when(positions.findAllByPlayerIdIn(List.of(10L))).thenReturn(List.of(
                position(10L, "MC", 20, true),
                position(10L, "DM", 12, true)));
        when(roles.findAllByPlayerIdIn(List.of(10L))).thenReturn(List.of());
        when(feet.findAllByPlayerIdIn(List.of(10L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.load(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple primary");
    }

    @Test
    void batchLoadingUsesCollectionQueriesAndReturnsDeterministicIds() {
        when(humans.findAllById(List.of(1L, 2L))).thenReturn(List.of(human(2L, "DC", "Right"), human(1L, "GK", "Left")));
        when(positions.findAllByPlayerIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(roles.findAllByPlayerIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(feet.findAllByPlayerIdIn(List.of(1L, 2L))).thenReturn(List.of());

        Map<Long, PlayerCapabilitySnapshot> snapshots = service.loadAll(List.of(2L, 1L, 2L));

        assertThat(snapshots.keySet()).containsExactly(1L, 2L);
        verify(humans).findAllById(List.of(1L, 2L));
        verify(positions).findAllByPlayerIdIn(List.of(1L, 2L));
        verify(roles).findAllByPlayerIdIn(List.of(1L, 2L));
        verify(feet).findAllByPlayerIdIn(List.of(1L, 2L));
    }

    @Test
    void preferredFootFallbacksMatchLegacyContract() {
        assertThat(PlayerCapabilityService.legacyFootRatings("Right")).isEqualTo(new PlayerCapabilityService.FootRatings(8, 20));
        assertThat(PlayerCapabilityService.legacyFootRatings("Left")).isEqualTo(new PlayerCapabilityService.FootRatings(20, 8));
        assertThat(PlayerCapabilityService.legacyFootRatings("Both")).isEqualTo(new PlayerCapabilityService.FootRatings(16, 16));
        assertThat(PlayerCapabilityService.legacyFootRatings(null)).isEqualTo(new PlayerCapabilityService.FootRatings(8, 20));
        assertThat(PlayerCapabilityService.legacyFootRatings("Unknown")).isEqualTo(new PlayerCapabilityService.FootRatings(8, 20));
    }

    @Test
    void publicFallbackMethodsDelegateToCanonicalResolver() {
        PlayerCapabilityResolver resolver = new PlayerCapabilityResolver(config);
        PlayerCapabilitySnapshot snapshot = new PlayerCapabilitySnapshot(15L, PlayerPosition.ST,
                Map.of(PlayerPosition.ST, 17), Map.of(), 8, 20, false, true, true);

        assertThat(service.fallbackPositionFamiliarity("ST", PlayerPosition.MC))
                .isEqualTo(resolver.fallbackPositionFamiliarity("ST", PlayerPosition.MC));
        assertThat(service.positionFamiliarityOrFallback(snapshot, PlayerPosition.MC))
                .isEqualTo(resolver.positionFamiliarityOrFallback(snapshot, PlayerPosition.MC));
        assertThat(service.roleFamiliarityOrFallback(snapshot, PlayerPosition.ST, PlayerRole.POACHER))
                .isEqualTo(resolver.roleFamiliarityOrFallback(snapshot, PlayerPosition.ST, PlayerRole.POACHER));
    }

    private static Human human(long id, String position, String preferredFoot) {
        Human human = new Human();
        human.setId(id);
        human.setTypeId(1L);
        human.setPosition(position);
        human.setPreferredFoot(preferredFoot);
        return human;
    }

    private static PlayerPositionFamiliarity position(long playerId, String positionCode, int familiarity, boolean primary) {
        PlayerPositionFamiliarity row = new PlayerPositionFamiliarity();
        row.setPlayerId(playerId);
        row.setPositionCode(positionCode);
        row.setFamiliarity(familiarity);
        row.setPrimaryPosition(primary);
        return row;
    }

    private static PlayerRoleFamiliarity role(long playerId, String positionCode, String roleCode, int familiarity) {
        PlayerRoleFamiliarity row = new PlayerRoleFamiliarity();
        row.setPlayerId(playerId);
        row.setPositionCode(positionCode);
        row.setRoleCode(roleCode);
        row.setFamiliarity(familiarity);
        return row;
    }

    private static PlayerFootProfile foot(long playerId, int left, int right) {
        PlayerFootProfile profile = new PlayerFootProfile();
        profile.setPlayerId(playerId);
        profile.setLeftFootRating(left);
        profile.setRightFootRating(right);
        return profile;
    }
}
