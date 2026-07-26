package com.footballmanagergamesimulator.multiplayer;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/** One continuous room-scoped worker; the scheduler never owns rapid days. */
@Service
public class RoomRapidFastForwardService {
    private final RoomContinueCoordinator coordinator;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "room-rapid-ff");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();

    public RoomRapidFastForwardService(RoomContinueCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void start(Long roomId) {
        progress.compute(roomId, (id, current) -> {
            if (current != null && current.status() == Status.RUNNING) return current;
            executor.submit(() -> run(id));
            return new Progress(Status.RUNNING, 0, 0, 0, 0, false);
        });
    }

    public void recoverPersistentWorker() {
        try { coordinator.persistedRapidRoom().ifPresent(this::start); }
        catch (RuntimeException ignored) { /* room may be between lifecycle transactions */ }
    }

    private void run(Long roomId) {
        try {
            while (coordinator.rapidEligible(roomId)) {
                AdvanceClaim claim = coordinator.claimRapidForRoom(roomId);
                if (claim == null) {
                    progress.computeIfPresent(roomId, (id, p) -> new Progress(Status.CANCEL_PENDING,
                            p.currentSeason(), p.currentDay(), p.targetSeason(), p.targetDay(), true));
                    LockSupport.parkNanos(5_000_000L);
                    continue;
                }
                coordinator.advanceClaimed(claim);
                progress.computeIfPresent(roomId, (id, p) -> new Progress(Status.RUNNING,
                        p.currentSeason(), p.currentDay(), p.targetSeason(), p.targetDay(), false));
            }
        } finally {
            progress.computeIfPresent(roomId, (id, p) -> new Progress(Status.IDLE,
                    p.currentSeason(), p.currentDay(), p.targetSeason(), p.targetDay(), p.cancelPending()));
        }
    }

    public Progress state(Long roomId, RoomDate currentDate, RoomDate targetDate) {
        Progress p = progress.get(roomId);
        if (p == null) return new Progress(Status.IDLE,
                currentDate == null ? 0 : currentDate.season(), currentDate == null ? 0 : currentDate.day(),
                targetDate == null ? 0 : targetDate.season(), targetDate == null ? 0 : targetDate.day(), false);
        return new Progress(p.status(),
                currentDate == null ? p.currentSeason() : currentDate.season(), currentDate == null ? p.currentDay() : currentDate.day(),
                targetDate == null ? p.targetSeason() : targetDate.season(), targetDate == null ? p.targetDay() : targetDate.day(), p.cancelPending());
    }

    @PreDestroy
    void stop() { executor.shutdownNow(); }

    public enum Status { IDLE, RUNNING, CANCEL_PENDING }
    public record Progress(Status status, int currentSeason, int currentDay, int targetSeason, int targetDay, boolean cancelPending) { }
}
