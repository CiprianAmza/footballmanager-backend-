package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Optional fast-simulation store: the application continues to use its normal
 * JPA repositories, but their primary DataSource is H2 in memory. A complete,
 * dated H2 snapshot is captured at a matchday boundary and uploaded to a
 * separate PostgreSQL database on one background worker.
 */
@Service
public class MemoryCheckpointService {

    public record Status(boolean enabled, int intervalMatchdays, int matchdaysSinceCheckpoint,
                         String state, String message, Integer season, Integer day,
                         Instant capturedAt, Instant persistedAt) {}

    private final boolean enabled;
    private final String postgresUrl;
    private final String postgresUsername;
    private final String postgresPassword;
    private final PrebuiltDataService snapshots;
    private final GameCalendarRepository calendars;
    private final AtomicInteger intervalMatchdays;
    private final AtomicInteger matchdaysSinceCheckpoint = new AtomicInteger();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Status> status;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "postgres-checkpoint-writer");
        thread.setDaemon(false);
        return thread;
    });

    public MemoryCheckpointService(
            @Value("${game.checkpoint.enabled:false}") boolean enabled,
            @Value("${game.checkpoint.interval-matchdays:5}") int interval,
            @Value("${game.checkpoint.postgres-url:}") String postgresUrl,
            @Value("${game.checkpoint.postgres-username:}") String postgresUsername,
            @Value("${game.checkpoint.postgres-password:}") String postgresPassword,
            PrebuiltDataService snapshots,
            GameCalendarRepository calendars) {
        if (interval < 1 || interval > 1000) {
            throw new IllegalArgumentException("checkpoint interval must be between 1 and 1000 matchdays");
        }
        if (enabled && postgresUrl.isBlank()) {
            throw new IllegalArgumentException("CHECKPOINT_DB_URL is required for fast-sim mode");
        }
        this.enabled = enabled;
        this.postgresUrl = postgresUrl;
        this.postgresUsername = postgresUsername;
        this.postgresPassword = postgresPassword;
        this.snapshots = snapshots;
        this.calendars = calendars;
        this.intervalMatchdays = new AtomicInteger(interval);
        this.status = new AtomicReference<>(new Status(enabled, interval, 0,
                enabled ? "IDLE" : "DISABLED", "", null, null, null, null));
    }

    public Status status() { return status.get(); }

    public Status setInterval(int matchdays) {
        if (!enabled) throw new IllegalStateException("Fast-simulation mode is disabled");
        if (matchdays < 1 || matchdays > 1000) {
            throw new IllegalArgumentException("interval must be between 1 and 1000 matchdays");
        }
        try (Connection connection = postgresConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE game_checkpoint_config SET interval_matchdays=? WHERE id=1")) {
            statement.setInt(1, matchdays);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Checkpoint configuration row is missing");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not save checkpoint interval", error);
        }
        intervalMatchdays.set(matchdays);
        Status previous = status.get();
        Status updated = new Status(true, matchdays, matchdaysSinceCheckpoint.get(),
                previous.state(), previous.message(), previous.season(), previous.day(),
                previous.capturedAt(), previous.persistedAt());
        status.set(updated);
        return updated;
    }

    /** Called before GameStateService decides to generate a fresh world. */
    public void restoreLatest() {
        if (!enabled) return;
        ensureTable();
        status.set(new Status(true, intervalMatchdays.get(), 0,
                "IDLE", "", null, null, null, null));
        try (Connection connection = postgresConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT season, game_day, captured_at, sha256, payload "
                             + "FROM game_checkpoint ORDER BY id DESC LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return;
            int season = rows.getInt(1);
            int day = rows.getInt(2);
            Instant capturedAt = rows.getTimestamp(3).toInstant();
            String expectedHash = rows.getString(4);
            Path script = Files.createTempFile("fm-checkpoint-restore-", ".sql");
            try {
                try (InputStream compressed = new GZIPInputStream(rows.getBinaryStream(5));
                     OutputStream target = Files.newOutputStream(script)) {
                    compressed.transferTo(target);
                }
                if (!sha256(script).equals(expectedHash)) {
                    throw new IllegalStateException("Checkpoint checksum does not match");
                }
                snapshots.restore(script);
            } finally {
                Files.deleteIfExists(script);
            }
            status.set(new Status(true, intervalMatchdays.get(), 0, "RESTORED", "",
                    season, day, capturedAt, capturedAt));
        } catch (SQLException | IOException error) {
            // Never silently generate a new world when a checkpoint cannot be read.
            throw new IllegalStateException("Could not restore the latest PostgreSQL checkpoint", error);
        }
    }

    /** Counts one game date with matches, even if multiple competitions played. */
    public void afterAdvance(Map<String, Object> result) {
        if (!enabled) return;
        Object events = result.get("eventsProcessed");
        if (!(events instanceof List<?> list)) return;
        boolean matchday = list.stream().anyMatch(item -> item instanceof Map<?, ?> event
                && "MATCH_DAY".equals(event.get("type")));
        boolean transition = list.stream().anyMatch(item -> item instanceof Map<?, ?> event
                && "SEASON_TRANSITION".equals(event.get("type")));
        if (matchday) matchdaysSinceCheckpoint.incrementAndGet();
        if ((matchday && matchdaysSinceCheckpoint.get() >= intervalMatchdays.get()) || transition) {
            try {
                capture(false);
            } catch (RuntimeException error) {
                // The game day is already committed in H2. Keep it playable and
                // expose the checkpoint failure through status for a manual retry.
                System.err.println("Checkpoint capture failed: " + error.getMessage());
                error.printStackTrace();
            }
        } else if (matchday) {
            Status previous = status.get();
            status.set(new Status(true, intervalMatchdays.get(), matchdaysSinceCheckpoint.get(),
                    previous.state(), previous.message(), previous.season(), previous.day(),
                    previous.capturedAt(), previous.persistedAt()));
        }
    }

    /** Must be called under GameLock; the SQL script is the immutable point-in-time cut. */
    public Status capture(boolean manual) {
        if (!enabled) throw new IllegalStateException("Fast-simulation mode is disabled");
        if (!busy.compareAndSet(false, true)) {
            if (manual) throw new IllegalStateException("A checkpoint is already being saved");
            return status.get();
        }
        Path script = null;
        try {
            GameCalendar date = calendars.findTopByOrderBySeasonDesc()
                    .orElseThrow(() -> new IllegalStateException("No game calendar to checkpoint"));
            script = Files.createTempFile("fm-checkpoint-", ".sql");
            snapshots.save(script);
            Instant capturedAt = Instant.now();
            int season = date.getSeason();
            int day = date.getCurrentDay();
            Status previous = status.get();
            status.set(new Status(true, intervalMatchdays.get(), matchdaysSinceCheckpoint.get(),
                    "SAVING", "", season, day, capturedAt, previous.persistedAt()));
            Path capturedScript = script;
            int capturedMatchdays = matchdaysSinceCheckpoint.get();
            writer.execute(() -> upload(capturedScript, season, day, capturedAt, capturedMatchdays));
            return status.get();
        } catch (Exception error) {
            busy.set(false);
            if (script != null) {
                try { Files.deleteIfExists(script); } catch (IOException ignored) { }
            }
            Status previous = status.get();
            status.set(new Status(true, intervalMatchdays.get(), matchdaysSinceCheckpoint.get(),
                    "FAILED", error.getMessage(), previous.season(), previous.day(),
                    previous.capturedAt(), previous.persistedAt()));
            throw new IllegalStateException("Could not capture in-memory game state", error);
        }
    }

    private void upload(Path script, int season, int day, Instant capturedAt, int capturedMatchdays) {
        Path gzip = null;
        try {
            String hash = sha256(script);
            gzip = Files.createTempFile("fm-checkpoint-upload-", ".gz");
            try (InputStream source = Files.newInputStream(script);
                 OutputStream compressed = new GZIPOutputStream(Files.newOutputStream(gzip))) {
                source.transferTo(compressed);
            }
            try (Connection connection = postgresConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO game_checkpoint (season, game_day, captured_at, sha256, payload) "
                                 + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setInt(1, season);
                statement.setInt(2, day);
                statement.setTimestamp(3, Timestamp.from(capturedAt));
                statement.setString(4, hash);
                try (InputStream payload = Files.newInputStream(gzip)) {
                    statement.setBinaryStream(5, payload, Files.size(gzip));
                    statement.executeUpdate();
                }
            }
            int remaining = matchdaysSinceCheckpoint.updateAndGet(
                    current -> Math.max(0, current - capturedMatchdays));
            status.set(new Status(true, intervalMatchdays.get(), remaining, "SAVED", "",
                    season, day, capturedAt, Instant.now()));
        } catch (Exception error) {
            Status previous = status.get();
            status.set(new Status(true, intervalMatchdays.get(), matchdaysSinceCheckpoint.get(),
                    "FAILED", error.getMessage(), season, day, capturedAt, previous.persistedAt()));
        } finally {
            busy.set(false);
            try { Files.deleteIfExists(script); } catch (IOException ignored) { }
            if (gzip != null) {
                try { Files.deleteIfExists(gzip); } catch (IOException ignored) { }
            }
        }
    }

    private void ensureTable() {
        try (Connection connection = postgresConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS game_checkpoint ("
                    + "id BIGSERIAL PRIMARY KEY, season INTEGER NOT NULL, game_day INTEGER NOT NULL, "
                    + "captured_at TIMESTAMPTZ NOT NULL, sha256 VARCHAR(64) NOT NULL, "
                    + "payload BYTEA NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS game_checkpoint_config ("
                    + "id INTEGER PRIMARY KEY, interval_matchdays INTEGER NOT NULL "
                    + "CHECK (interval_matchdays BETWEEN 1 AND 1000))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO game_checkpoint_config (id, interval_matchdays) VALUES (1, ?) "
                            + "ON CONFLICT (id) DO NOTHING")) {
                insert.setInt(1, intervalMatchdays.get());
                insert.executeUpdate();
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT interval_matchdays FROM game_checkpoint_config WHERE id=1")) {
                if (row.next()) intervalMatchdays.set(row.getInt(1));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not initialize PostgreSQL checkpoint storage", error);
        }
    }

    private Connection postgresConnection() throws SQLException {
        return DriverManager.getConnection(postgresUrl, postgresUsername, postgresPassword);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException error) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
