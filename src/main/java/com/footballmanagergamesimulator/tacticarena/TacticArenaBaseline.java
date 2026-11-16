package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The base world every run starts from: a newly generated world at season 1, day 1,
 * generated once in a child process on an empty database and kept in
 * {@code tactic-arena.baseline-dir} ({@code world.zip}, {@code meta.json},
 * {@code clubs.json}, {@code setup/<teamId>.json.gz}).
 *
 * <p>Several backends may share the folder. Generating is guarded by
 * {@code <baseline-dir>.lock} (created atomically; it names the owning process, and a
 * lock whose process is gone, or older than {@link #STALE_LOCK}, is taken over). Reading
 * needs no lock: a new world is assembled in a side folder and moved in place at the end.
 */
@Component
public class TacticArenaBaseline {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaBaseline.class);
    /** Rough duration of a new world's startup, for the progress estimate. */
    private static final double EXPECTED_STARTUP_SECONDS = 480;
    private static final Duration STALE_LOCK = Duration.ofHours(2);

    public static final String READY = "READY";
    public static final String BUILDING = "BUILDING";
    public static final String MISSING = "MISSING";
    public static final String FAILED = "FAILED";

    @Autowired private TacticArenaProcesses processes;
    @Autowired private ObjectMapper objectMapper;

    @Value("${tactic-arena.baseline-dir:}") private String baselineDirSetting;

    private Process build;
    private Instant buildStartedAt;
    private String lastError;
    private Instant lastErrorAt;
    private JsonNode meta;
    private long metaModified = -1;

    Path dir() {
        if (baselineDirSetting != null && !baselineDirSetting.isBlank()) return Path.of(baselineDirSetting.trim()).toAbsolutePath();
        return processes.workDir().resolve("baseline").toAbsolutePath();
    }

    private Path lockFile() {
        return dir().resolveSibling(dir().getFileName() + ".lock");
    }

    private Path buildDir() {
        return dir().resolveSibling(dir().getFileName() + ".build-" + ProcessHandle.current().pid());
    }

    // ---------------------------------------------------------------- lock

    private record LockInfo(long pid, String host, Instant startedAt) {}

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private LockInfo readLock() {
        try {
            String[] parts = Files.readString(lockFile(), StandardCharsets.UTF_8).trim().split("\\s+");
            return new LockInfo(Long.parseLong(parts[0]), parts[1], Instant.parse(parts[2]));
        } catch (NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            // unreadable (being written): treat as a fresh lock of an unknown process
            return new LockInfo(-1, "?", Instant.now());
        }
    }

    private boolean stale(LockInfo lock) {
        if (lock.startedAt().isBefore(Instant.now().minus(STALE_LOCK))) return true;
        return lock.pid() > 0 && host().equals(lock.host())
                && !ProcessHandle.of(lock.pid()).map(ProcessHandle::isAlive).orElse(false);
    }

    /** The lock held by another live process, or null. */
    private LockInfo otherBuilder() {
        LockInfo lock = readLock();
        if (lock == null || lock.pid() == ProcessHandle.current().pid() || stale(lock)) return null;
        return lock;
    }

    private boolean acquireLock() throws Exception {
        Files.createDirectories(lockFile().getParent());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.writeString(lockFile(), ProcessHandle.current().pid() + " " + host() + " " + Instant.now(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return true;
            } catch (FileAlreadyExistsException e) {
                LockInfo lock = readLock();
                if (lock != null && !stale(lock)) return false;
                log.info("[tactic-arena] taking over a stale base-world lock {}", lock);
                Files.deleteIfExists(lockFile());
            }
        }
        return false;
    }

    private void releaseLock() {
        LockInfo lock = readLock();
        if (lock != null && lock.pid() == ProcessHandle.current().pid()) {
            try {
                Files.deleteIfExists(lockFile());
            } catch (Exception ignored) {
                // a stale lock is taken over later
            }
        }
    }

    // ---------------------------------------------------------------- state

    /** Remove half-built worlds of dead processes (this one's included, from before a restart). */
    synchronized void cleanupAfterRestart() {
        Path parent = dir().getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        String prefix = dir().getFileName() + ".build-";
        try (Stream<Path> files = Files.list(parent)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith(prefix)) continue;
                try {
                    long pid = Long.parseLong(name.substring(prefix.length()));
                    boolean alive = pid != ProcessHandle.current().pid()
                            && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                    if (!alive) TacticArenaService.deleteQuietly(p);
                } catch (NumberFormatException ignored) {
                    // not ours
                }
            }
        } catch (Exception ignored) {
            // nothing to clean
        }
    }

    synchronized boolean ready() {
        return build == null && meta() != null;
    }

    /** True while this or another backend generates the base world. */
    synchronized boolean building() {
        return build != null || otherBuilder() != null;
    }

    synchronized String status() {
        if (building()) return BUILDING;
        if (meta() != null) return READY;
        return lastError != null ? FAILED : MISSING;
    }

    synchronized String lastError() {
        return lastError;
    }

    synchronized JsonNode meta() {
        Path file = dir().resolve("meta.json");
        try {
            if (!Files.exists(file)) {
                meta = null;
                metaModified = -1;
                return null;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (meta == null || modified != metaModified) {
                meta = objectMapper.readTree(file.toFile());
                metaModified = modified;
            }
        } catch (Exception e) {
            meta = null;
        }
        return meta;
    }

    String uid() {
        JsonNode m = meta();
        return m == null ? null : m.path("uid").asText(null);
    }

    /** Start generating a new base world, unless another backend already does. */
    synchronized void startBuild() {
        if (build != null) return;
        Path work = buildDir();
        try {
            if (!acquireLock()) return; // someone else generates it; we wait for meta.json
            TacticArenaService.deleteQuietly(work);
            Path db = work.resolve("db");
            Path out = work.resolve("out");
            Files.createDirectories(db);
            Files.createDirectories(out);
            build = processes.start(work, db, "fm", Map.of(
                    "tactic-arena.child.baseline-dir", out.toAbsolutePath().toString(),
                    "match2d.mode", "off"));
            buildStartedAt = Instant.now();
            lastError = null;
            lastErrorAt = null;
            log.info("[tactic-arena] generating a new base world in {}, pid {}", work, build.pid());
        } catch (Exception e) {
            build = null;
            releaseLock();
            lastError = "Could not start the base world generation: " + e.getMessage();
            lastErrorAt = Instant.now();
        }
    }

    /** Delete the current base world and generate a new one. */
    synchronized void rebuild() {
        if (building()) return;
        meta = null;
        TacticArenaService.deleteQuietly(dir());
        startBuild();
    }

    /** Called by the scheduler; finishes a build whose process has exited. */
    synchronized void poll() {
        if (build == null || build.isAlive()) return;
        int exit = build.exitValue();
        build = null;
        Path work = buildDir();
        Path out = work.resolve("out");
        try {
            if (exit == 0 && Files.exists(out.resolve("meta.json")) && Files.exists(out.resolve("world.zip"))) {
                try {
                    meta = null;
                    TacticArenaService.deleteQuietly(dir());
                    Files.move(out, dir(), StandardCopyOption.ATOMIC_MOVE);
                    TacticArenaService.deleteQuietly(work);
                    log.info("[tactic-arena] base world ready in {} ({} s)", dir(),
                            Duration.between(buildStartedAt, Instant.now()).toSeconds());
                    return;
                } catch (Exception e) {
                    lastError = "Could not keep the new base world: " + e.getMessage();
                }
            } else {
                String error = null;
                try {
                    Path file = out.resolve("error.txt");
                    if (Files.exists(file)) error = Files.readString(file, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // fall back to the exit code
                }
                lastError = "Base world generation failed" + (error == null ? " (exit code " + exit + ")" : ": " + error);
                TacticArenaService.deleteQuietly(work.resolve("db"));
            }
            lastErrorAt = Instant.now();
            log.warn("[tactic-arena] {}", lastError);
        } finally {
            releaseLock();
        }
    }

    synchronized void stop() {
        if (build != null) {
            TacticArenaProcesses.kill(build);
            releaseLock();
        }
    }

    /** Approximate progress of a build: startup by elapsed time (0–80), then the club pages (80–100). */
    synchronized double progress() {
        Instant started = buildStartedAt;
        Path progressFile = buildDir().resolve("out").resolve("progress.json");
        if (build == null) {
            LockInfo other = otherBuilder();
            if (other == null) return meta() != null ? 100 : 0;
            started = other.startedAt();
            progressFile = dir().resolveSibling(dir().getFileName() + ".build-" + other.pid()).resolve("out").resolve("progress.json");
        }
        double startup = Math.min(80, Duration.between(started, Instant.now()).toSeconds() / EXPECTED_STARTUP_SECONDS * 80);
        try {
            if (Files.exists(progressFile)) {
                JsonNode p = objectMapper.readTree(progressFile.toFile());
                if ("SETUPS".equals(p.path("stage").asText()) && p.path("total").asInt() > 0) {
                    return Math.min(99.9, 80 + 20.0 * p.path("done").asInt() / p.path("total").asInt());
                }
                return Math.max(startup, 80);
            }
        } catch (Exception ignored) {
            // being rewritten
        }
        return startup;
    }

    synchronized Map<String, Object> toJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        JsonNode m = build == null ? meta() : null;
        LockInfo other = build == null ? otherBuilder() : null;
        body.put("status", status());
        body.put("progress", Math.round(progress() * 10) / 10.0);
        body.put("uid", m == null ? null : m.path("uid").asText(null));
        body.put("createdAt", m == null ? null : m.path("createdAt").asText(null));
        body.put("appVersion", m == null || m.path("appVersion").isNull() ? null : m.path("appVersion").asText(null));
        body.put("season", m == null ? null : m.path("season").asInt());
        body.put("startDay", m == null ? null : m.path("startDay").asInt());
        body.put("endDay", m == null ? null : m.path("endDay").asInt());
        body.put("clubs", m == null ? null : m.path("clubs").asInt());
        body.put("buildStartedAt", build != null ? buildStartedAt.toString() : other != null ? other.startedAt().toString() : null);
        body.put("builtByOtherInstance", other != null);
        body.put("message", other != null ? "Being generated by another backend (pid " + other.pid() + ")" : lastError);
        return body;
    }

    // ---------------------------------------------------------------- data of the base world

    JsonNode clubs() {
        try {
            return objectMapper.readTree(dir().resolve("clubs.json").toFile());
        } catch (Exception e) {
            throw new IllegalStateException("The base world has no club list: " + e.getMessage(), e);
        }
    }

    /** {@code setup} of one club, or null when the club is not in the base world. */
    JsonNode setup(long teamId) {
        Path file = dir().resolve("setup").resolve(teamId + ".json.gz");
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readTree(TacticArenaSupport.gunzip(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read club " + teamId + " of the base world", e);
        }
    }

    Path worldZip() {
        return dir().resolve("world.zip");
    }

    /** The league row (with its teams, highest reputation first) that contains the club. */
    JsonNode leagueOf(long teamId) {
        for (JsonNode league : clubs().path("leagues")) {
            for (JsonNode team : league.path("teams")) {
                if (team.path("teamId").asLong() == teamId) return league;
            }
        }
        return null;
    }

    int leagueMatches(long teamId) {
        JsonNode m = meta();
        return m == null ? 0 : m.path("leagueMatchesByTeam").path(String.valueOf(teamId)).asInt();
    }
}
