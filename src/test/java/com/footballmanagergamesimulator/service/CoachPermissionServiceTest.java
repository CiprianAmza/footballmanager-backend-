package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CoachPermissions;
import com.footballmanagergamesimulator.service.CoachPermissionService.LockedSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the coach-permission guardian: a fresh team is fully permissive, and the
 * owner XI-lock JSON round-trips (with defensive parsing for missing/garbage input).
 */
class CoachPermissionServiceTest {

    private final CoachPermissionService service = new CoachPermissionService();

    @Test
    void defaultEntityIsFullyPermissive() {
        CoachPermissions p = new CoachPermissions();
        assertThat(p.isCanBuyPlayers()).isTrue();
        assertThat(p.isCanSellPlayers()).isTrue();
        assertThat(p.isCanNegotiateContracts()).isTrue();
        assertThat(p.isCanPickXI()).isTrue();
        assertThat(p.isCanChangeFormationTactics()).isTrue();
        assertThat(p.isCanSetTraining()).isTrue();
        assertThat(p.isCanSetSetPieces()).isTrue();
        assertThat(p.getTransferBudgetCap()).isEqualTo(-1);
        assertThat(p.getLockedSlots()).isNull();
    }

    @Test
    void lockedSlotsRoundTrip() {
        List<LockedSlot> locks = List.of(new LockedSlot(0, 100L), new LockedSlot(9, 200L));
        String json = service.writeLockedSlots(locks);
        List<LockedSlot> parsed = service.parseLockedSlots(json);
        assertThat(parsed).containsExactly(new LockedSlot(0, 100L), new LockedSlot(9, 200L));
    }

    @Test
    void parseLockedSlotsToleratesNullBlankAndGarbage() {
        assertThat(service.parseLockedSlots(null)).isEmpty();
        assertThat(service.parseLockedSlots("")).isEmpty();
        assertThat(service.parseLockedSlots("   ")).isEmpty();
        assertThat(service.parseLockedSlots("not json")).isEmpty();
    }

    @Test
    void writeLockedSlotsHandlesNull() {
        assertThat(service.parseLockedSlots(service.writeLockedSlots(null))).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("""
            GIVEN club 10 with two locked XI slots, club 20 with none stored and club 30 with no permissions row
            WHEN the locked slots of all three are read at once
            THEN one query returns club 10's two slots and an empty list for club 20; club 30 is simply absent
            """)
    void lockedSlotsOfManyClubsInOneQuery() {
        var repository = org.mockito.Mockito.mock(com.footballmanagergamesimulator.repository.CoachPermissionsRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "repository", repository);
        CoachPermissions ten = new CoachPermissions();
        ten.setTeamId(10L);
        ten.setLockedSlots(service.writeLockedSlots(List.of(new LockedSlot(0, 100L), new LockedSlot(9, 200L))));
        CoachPermissions twenty = new CoachPermissions();
        twenty.setTeamId(20L);
        org.mockito.Mockito.when(repository.findAllByTeamIdIn(List.of(10L, 20L, 30L))).thenReturn(List.of(ten, twenty));

        var locked = service.lockedSlotsByTeam(List.of(10L, 20L, 30L));

        assertThat(locked.get(10L)).containsExactly(new LockedSlot(0, 100L), new LockedSlot(9, 200L));
        assertThat(locked.get(20L)).isEmpty();
        assertThat(locked).doesNotContainKey(30L);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findByTeamId(org.mockito.ArgumentMatchers.anyLong());
    }
}
