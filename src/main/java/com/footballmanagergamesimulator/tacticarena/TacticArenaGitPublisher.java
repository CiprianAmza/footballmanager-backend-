package com.footballmanagergamesimulator.tacticarena;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional {@code tactic-arena.git-push}: after an export changes, commit the export folder
 * and push it, using the git installation and credentials already configured on this
 * computer. One git command at a time, on one thread, debounced so a burst of image
 * uploads becomes one push. A failure only logs a warning; the next request retries and
 * pushes whatever commit is still local.
 */
@Component
public class TacticArenaGitPublisher {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaGitPublisher.class);
    private static final long DEBOUNCE_SECONDS = 5;
    private static final long COMMAND_TIMEOUT_SECONDS = 120;

    public record GitPushStatus(boolean enabled, Instant lastAttemptAt, Boolean ok, String message) {}

    private final boolean enabled;
    private final String exportDir;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tactic-arena-git");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile String pendingMessage;
    private volatile GitPushStatus status;

    public TacticArenaGitPublisher(@Value("${tactic-arena.git-push:false}") boolean gitPush,
                                   @Value("${tactic-arena.export-dir:}") String exportDir,
                                   @Value("${tactic-arena.child.run-dir:}") String childRunDir) {
        this.exportDir = exportDir == null ? "" : exportDir.trim();
        this.enabled = gitPush && !this.exportDir.isEmpty() && (childRunDir == null || childRunDir.isBlank());
        this.status = new GitPushStatus(enabled, null, null, null);
    }

    public GitPushStatus status() {
        return status;
    }

    /** Ask for a commit + push of the export folder; requests within a few seconds are merged. */
    public void request(String commitMessage) {
        if (!enabled) return;
        pendingMessage = commitMessage;
        if (scheduled.compareAndSet(false, true)) {
            executor.schedule(this::publish, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void publish() {
        scheduled.set(false);
        String message = pendingMessage == null ? "Tactic Arena results" : pendingMessage;
        Instant at = Instant.now();
        try {
            Path dir = Path.of(exportDir);
            if (!Files.isDirectory(dir)) throw new IllegalStateException("export folder " + dir + " does not exist");
            Result inside = git(dir, "rev-parse", "--is-inside-work-tree");
            if (inside.code != 0) throw new IllegalStateException("export folder is not inside a git working copy");
            Result remotes = git(dir, "remote");
            if (remotes.code != 0 || remotes.output.isBlank()) throw new IllegalStateException("the repository has no remote");

            check(git(dir, "add", "-A", "."), "git add");
            Result staged = git(dir, "diff", "--cached", "--quiet", "--", ".");
            boolean committed = false;
            if (staged.code == 1) {
                check(git(dir, "commit", "-m", message, "--", "."), "git commit");
                committed = true;
            } else if (staged.code != 0) {
                check(staged, "git diff");
            }
            check(git(dir, "push"), "git push");
            status = new GitPushStatus(true, at, true, committed ? "Pushed: " + message : "Nothing new to commit; pushed");
            log.info("[tactic-arena] git push ok ({})", message);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status = new GitPushStatus(true, at, false, error);
            log.warn("[tactic-arena] git push failed: {}", error);
        }
    }

    private record Result(int code, String output) {}

    private static void check(Result result, String step) {
        if (result.code != 0) throw new IllegalStateException(step + " failed (exit " + result.code + "): " + result.output.trim());
    }

    private static Result git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        File out = File.createTempFile("tactic-arena-git", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(out)
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            Map<String, String> env = pb.environment();
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put("GCM_INTERACTIVE", "never");
            Process process = pb.start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(-1, "timed out after " + COMMAND_TIMEOUT_SECONDS + " s");
            }
            String text = Files.readString(out.toPath(), StandardCharsets.UTF_8);
            if (text.length() > 2000) text = text.substring(text.length() - 2000);
            return new Result(process.exitValue(), text);
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").toLowerCase().startsWith("windows") ? "NUL" : "/dev/null");
    }

    Map<String, Object> toJson() {
        GitPushStatus s = status;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", s.enabled());
        map.put("lastAttemptAt", s.lastAttemptAt() == null ? null : s.lastAttemptAt().toString());
        map.put("ok", s.ok());
        map.put("message", s.message());
        return map;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
