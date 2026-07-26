package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class RoomContinueSchedulerTest {
    @Test
    void tickRecoversPersistedRapidWorkerBeforeCheckingNormalAdvance() {
        RoomContinueCoordinator coordinator = mock(RoomContinueCoordinator.class);
        RoomRapidFastForwardService rapid = mock(RoomRapidFastForwardService.class);
        when(coordinator.claimExpired()).thenReturn(null);
        when(coordinator.recoverExpired()).thenReturn(null);

        new RoomContinueScheduler(coordinator, rapid).tick();

        verify(rapid).recoverPersistentWorker();
    }
}
