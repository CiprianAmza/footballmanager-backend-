package com.footballmanagergamesimulator.tacticarena;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Captures without a person: when a test is DONE, its page is opened in a headless
 * Edge/Chrome; the page draws the panels of every run and uploads the images itself
 * ({@code PUT .../images/{kind}}). The browser runs with its own temporary profile, one at a
 * time, and is stopped as soon as all images arrived or after the time limit. A failure
 * leaves the test without images (logged), nothing else.
 */
@Component
public class TacticArenaCapture {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaCapture.class);

    public record CaptureStatus(String status, String message, Instant at, int images, int expected) {}

    @Value("${tactic-arena.capture:auto}") private String mode;
    @Value("${tactic-arena.capture.browser:}") private String browserSetting;
    @Value("${tactic-arena.capture.page-url:}") private String pageUrlSetting;
    @Value("${tactic-arena.capture.timeout-seconds:300}") private int timeoutSeconds;
    @Value("${tactic-arena.ui-dir:}") private String uiDir;
    @Value("${tactic-arena.child.run-dir:}") private String childRunDir;
    @Value("${tactic-arena.child.baseline-dir:}") private String childBaselineDir;

    @Autowired private Environment environment;
    @Autowired private TacticArenaImageRepository imageRepository;
    @Autowired private TacticArenaRunRepository runRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tactic-arena-capture");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, CaptureStatus> statuses = new ConcurrentHashMap<>();
    private volatile Process current;

    /** Null when captures are on and possible, else the reason why not. */
    String unavailableReason() {
        if ("off".equalsIgnoreCase(mode.trim())) return "tactic-arena.capture=off";
        if (!blank(childRunDir) || !blank(childBaselineDir)) return "simulation process";
        if (pageBase() == null) return "the page address is unknown (set tactic-arena.ui-dir or tactic-arena.capture.page-url)";
        if (browser() == null) return "no Edge/Chrome found (set tactic-arena.capture.browser)";
        return null;
    }

    void request(long testId) {
        request(testId, null);
    }

    /**
     * Queue a capture; {@code beforeCapture} (e.g. deleting the old images) runs on the capture
     * thread right before the browser opens, so a long queue costs nothing up front.
     */
    void request(long testId, Runnable beforeCapture) {
        String reason = unavailableReason();
        if (reason != null) {
            statuses.put(testId, new CaptureStatus("OFF", reason, Instant.now(), images(testId), expected(testId)));
            return;
        }
        statuses.put(testId, new CaptureStatus("QUEUED", null, Instant.now(), images(testId), expected(testId)));
        executor.submit(() -> {
            if (beforeCapture != null) {
                try {
                    beforeCapture.run();
                } catch (RuntimeException e) {
                    log.warn("[tactic-arena] preparing the capture of test {} failed: {}", testId, e.toString());
                }
            }
            capture(testId);
        });
    }

    /** Images stored for the test / images it should have (3 per run). */
    int[] imageCount(long testId) {
        return new int[]{images(testId), expected(testId)};
    }

    Map<String, Object> toJson(long testId) {
        CaptureStatus s = statuses.get(testId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", s == null ? null : s.status());
        map.put("message", s == null ? null : s.message());
        map.put("at", s == null || s.at() == null ? null : s.at().toString());
        map.put("images", images(testId));
        map.put("expected", expected(testId));
        return map;
    }

    private void capture(long testId) {
        int expected = expected(testId);
        if (images(testId) >= expected) {
            statuses.put(testId, new CaptureStatus("DONE", "All images were already there", Instant.now(), expected, expected));
            return;
        }
        String url = pageBase() + "/tactic-arena/" + testId;
        Path profile = null;
        Instant started = Instant.now();
        statuses.put(testId, new CaptureStatus("RUNNING", url, started, images(testId), expected));
        try {
            profile = Files.createTempDirectory("tactic-arena-browser");
            List<String> command = new ArrayList<>(List.of(browser(),
                    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                    "--disable-extensions", "--disable-sync", "--hide-scrollbars", "--mute-audio",
                    "--window-size=1600,1200", "--user-data-dir=" + profile.toAbsolutePath(), url));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(profile.resolve("browser.log").toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                    .start();
            current = process;
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            int images = images(testId);
            while (images < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
                images = images(testId);
                if (!process.isAlive() && images < expected) {
                    // a launcher that handed over to another process: keep waiting for the upload until the deadline
                    if (System.currentTimeMillis() - started.toEpochMilli() > 60_000 && images == 0) break;
                }
            }
            String status = images >= expected ? "DONE" : "FAILED";
            String message = images >= expected
                    ? "Captured in " + (System.currentTimeMillis() - started.toEpochMilli()) / 1000 + " s"
                    : "Only " + images + " of " + expected + " images arrived within " + timeoutSeconds + " s";
            statuses.put(testId, new CaptureStatus(status, message, Instant.now(), images, expected));
            if (images >= expected) log.info("[tactic-arena] test {}: {}", testId, message);
            else log.warn("[tactic-arena] capture of test {}: {}", testId, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            statuses.put(testId, new CaptureStatus("FAILED", e.toString(), Instant.now(), images(testId), expected));
            log.warn("[tactic-arena] capture of test {} failed: {}", testId, e.toString());
        } finally {
            Process process = current;
            current = null;
            if (process != null) TacticArenaProcesses.kill(process);
            if (profile != null) {
                Path dir = profile;
                // the browser releases its profile files a moment after it is killed
                for (int i = 0; i < 5 && Files.exists(dir); i++) {
                    TacticArenaService.deleteQuietly(dir);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private int images(long testId) {
        return imageRepository.kindsForTest(testId).size();
    }

    private int expected(long testId) {
        return runRepository.findAllByTestIdOrderByRunIndexAsc(testId).size() * TacticArenaSupport.IMAGE_KINDS.size();
    }

    private String pageBase() {
        if (!blank(pageUrlSetting)) return pageUrlSetting.trim().replaceAll("/+$", "");
        if (blank(uiDir)) return null;
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port"));
        return port == null || "0".equals(port) ? null : "http://localhost:" + port;
    }

    private String cachedBrowser;

    synchronized String browser() {
        if (!blank(browserSetting)) return browserSetting.trim();
        if (cachedBrowser != null) return cachedBrowser.isEmpty() ? null : cachedBrowser;
        List<String> candidates = new ArrayList<>();
        String pf = System.getenv("ProgramFiles"), pf86 = System.getenv("ProgramFiles(x86)"), local = System.getenv("LOCALAPPDATA");
        for (String root : new String[]{pf86, pf, local}) {
            if (root == null) continue;
            candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
            candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
        String found = candidates.stream().filter(p -> new File(p).canExecute()).findFirst().orElse(null);
        if (found == null) {
            String path = Optional.ofNullable(System.getenv("PATH")).orElse("");
            outer:
            for (String name : new String[]{"google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge"}) {
                for (String dir : path.split(File.pathSeparator)) {
                    File f = new File(dir, name);
                    if (f.canExecute()) {
                        found = f.getAbsolutePath();
                        break outer;
                    }
                }
            }
        }
        cachedBrowser = found == null ? "" : found;
        return found;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }

    @PreDestroy
    void shutdown() {
        Process process = current;
        if (process != null) TacticArenaProcesses.kill(process);
        executor.shutdownNow();
    }
}
