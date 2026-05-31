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
}
