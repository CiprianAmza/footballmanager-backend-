package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs unattended calendar advancement inside the backend. There is no HTTP
 * round-trip or browser delay between days; the UI only polls this job's state.
 */
@Service
public class FastForwardService {

    public record FastForwardStatus(
            String jobId,
            String status,
            int seasonsRequested,
            int startSeason,
            int targetSeason,
            int currentSeason,
            int currentDay,
            String currentPhase,
            int completedSeasons,
            long processedDays,
            double percent,
            long elapsedMs,
            String message,
            boolean cancellable
    ) {}

    @Autowired private GameAdvanceService gameAdvanceService;
    @Autowired private GameCalendarRepository gameCalendarRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "season-fast-forward");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<FastForwardStatus> latest = new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private volatile long estimatedTotalDays = 1;

    public synchronized FastForwardStatus start(int seasons, int chunkDays) {
        if (seasons < 1 || seasons > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seasons must be between 1 and 100");
        }
        if (chunkDays < 1 || chunkDays > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chunkDays must be between 1 and 30");
        }
        FastForwardStatus active = latest.get();
        if (active != null && "RUNNING".equals(active.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A fast-forward job is already running");
        }
        if (!gameAdvanceService.isAlwaysContinueActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Enable Always Continue for every human manager before fast-forwarding");
        }

        GameCalendar calendar = currentCalendar();
        String jobId = UUID.randomUUID().toString();
        int targetSeason = calendar.getSeason() + seasons;
        long startedAt = System.nanoTime();
        estimatedTotalDays = Math.max(1L, (361L - calendar.getCurrentDay()) + (long) (seasons - 1) * 360L);
        cancellationRequested.set(false);

        FastForwardStatus initial = new FastForwardStatus(
                jobId, "RUNNING", seasons, calendar.getSeason(), targetSeason,
                calendar.getSeason(), calendar.getCurrentDay(), calendar.getCurrentPhase(),
                0, 0, 0, 0, "Fast-forward started", true);
        latest.set(initial);
        executor.submit(() -> run(jobId, seasons, targetSeason, chunkDays, startedAt));
        return initial;
    }

    public FastForwardStatus getStatus() {
        FastForwardStatus status = latest.get();
        if (status != null) return status;

        GameCalendar calendar = currentCalendar();
        return new FastForwardStatus(
                null, "IDLE", 0, calendar.getSeason(), calendar.getSeason(),
                calendar.getSeason(), calendar.getCurrentDay(), calendar.getCurrentPhase(),
                0, 0, 0, 0, "No fast-forward job has been started", false);
    }

    /**
     * Import/restart guards need to know whether a worker is active without
     * resolving the current calendar. A freshly started backend may not yet
     * have a calendar matching the season contained in the save being loaded.
     */
    public boolean isRunning() {
        FastForwardStatus status = latest.get();
        return status != null && "RUNNING".equals(status.status());
    }

    public synchronized FastForwardStatus cancel(String jobId) {
        FastForwardStatus status = latest.get();
        if (status == null || status.jobId() == null || !status.jobId().equals(jobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fast-forward job not found");
        }
        if ("RUNNING".equals(status.status())) {
            cancellationRequested.set(true);
            latest.set(copy(status, "RUNNING", status.percent(), status.elapsedMs(),
                    "Cancellation requested; finishing the current day", true));
        }
        return latest.get();
    }

    private void run(String jobId, int seasons, int targetSeason, int chunkDays, long startedAt) {
        long processedDays = 0;
        try {
            while (true) {
                GameCalendar beforeChunk = currentCalendar();
                if (beforeChunk.getSeason() >= targetSeason) {
                    publish(jobId, "COMPLETED", seasons, targetSeason, beforeChunk,
                            processedDays, 100, startedAt, "Fast-forward completed", false);
                    return;
                }

                for (int day = 0; day < chunkDays; day++) {
                    if (cancellationRequested.get()) {
                        GameCalendar stopped = currentCalendar();
                        publish(jobId, "CANCELLED", seasons, targetSeason, stopped,
                                processedDays, progress(processedDays, targetSeason, stopped), startedAt,
                                "Fast-forward cancelled", false);
                        return;
                    }

                    GameCalendar before = currentCalendar();
                    Map<String, Object> result = gameAdvanceService.advance(before.getSeason());
                    GameCalendar after = currentCalendar();
                    processedDays++;

                    if (Boolean.TRUE.equals(result.get("paused"))) {
                        throw new IllegalStateException("Simulation paused: "
                                + result.getOrDefault("reason", "unknown reason"));
                    }
                    if (after.getSeason() == before.getSeason()
                            && after.getCurrentDay() == before.getCurrentDay()
                            && after.getCurrentPhase().equals(before.getCurrentPhase())) {
                        throw new IllegalStateException("Calendar did not advance");
                    }
                    if (after.getSeason() >= targetSeason) break;
                }

                GameCalendar current = currentCalendar();
                publish(jobId, "RUNNING", seasons, targetSeason, current, processedDays,
                        progress(processedDays, targetSeason, current), startedAt,
                        "Simulating season " + current.getSeason() + ", day " + current.getCurrentDay(), true);
            }
        } catch (Exception exception) {
            GameCalendar current = currentCalendar();
            publish(jobId, "FAILED", seasons, targetSeason, current, processedDays,
                    progress(processedDays, targetSeason, current), startedAt,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), false);
        }
    }

    private double progress(long processedDays, int targetSeason, GameCalendar current) {
        if (current.getSeason() >= targetSeason) return 100;
        return Math.round(Math.min(99.9, processedDays * 1000.0 / estimatedTotalDays)) / 10.0;
    }

    private void publish(String jobId, String status, int seasons, int targetSeason,
                         GameCalendar calendar, long processedDays, double percent,
                         long startedAt, String message, boolean cancellable) {
        FastForwardStatus previous = latest.get();
        int startSeason = previous == null ? calendar.getSeason() : previous.startSeason();
        latest.set(new FastForwardStatus(
                jobId, status, seasons, startSeason, targetSeason,
                calendar.getSeason(), calendar.getCurrentDay(), calendar.getCurrentPhase(),
                Math.min(seasons, Math.max(0, calendar.getSeason() - startSeason)),
                processedDays, percent, elapsedMs(startedAt), message, cancellable));
    }

    private FastForwardStatus copy(FastForwardStatus value, String status, double percent,
                                   long elapsedMs, String message, boolean cancellable) {
        return new FastForwardStatus(
                value.jobId(), status, value.seasonsRequested(), value.startSeason(), value.targetSeason(),
                value.currentSeason(), value.currentDay(), value.currentPhase(), value.completedSeasons(),
                value.processedDays(), percent, elapsedMs, message, cancellable);
    }

    private GameCalendar currentCalendar() {
        return gameCalendarRepository.findTopByOrderBySeasonDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The game must be initialized before fast-forwarding"));
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    @PreDestroy
    void shutdown() {
        cancellationRequested.set(true);
        executor.shutdownNow();
    }
}
