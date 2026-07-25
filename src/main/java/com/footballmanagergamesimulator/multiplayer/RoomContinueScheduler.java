package com.footballmanagergamesimulator.multiplayer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoomContinueScheduler {
    private final RoomContinueCoordinator coordinator;
    public RoomContinueScheduler(RoomContinueCoordinator coordinator) { this.coordinator = coordinator; }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        try {
            AdvanceClaim claim = coordinator.claimExpired();
            if (claim == null) claim = coordinator.recoverExpired();
            if (claim != null) coordinator.advanceClaimed(claim);
        }
        catch (Exception ignored) { /* hard failures are persisted on the cycle; do not spin */ }
    }
}
