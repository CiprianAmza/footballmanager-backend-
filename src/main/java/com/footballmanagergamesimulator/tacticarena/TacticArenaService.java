package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.service.TacticService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.footballmanagergamesimulator.tacticarena.TacticArenaEntities.*;

/**
 * Parent side of Tactic Arena: queue, base world, child processes, progress, results and
 * the read models behind {@code /tactic-arena}.
 *
 * <p>Every run starts from a fresh copy of the base world ({@link TacticArenaBaseline}: a
 * new world at season 1, day 1) in its own JVM and plays the whole of season 1. Nothing
 * reads or writes the career the user is playing; the backend's own database only stores
 * the tests.
 */
@Service
public class TacticArenaService {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaService.class);
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    @Autowired private TacticArenaTestRepository testRepository;
    @Autowired private TacticArenaRunRepository runRepository;
    @Autowired private TacticArenaRunResultRepository resultRepository;
    @Autowired private TacticArenaImageRepository imageRepository;
    @Autowired private TacticArenaExporter exporter;
    @Autowired private TacticArenaGitPublisher gitPublisher;
    @Autowired private TacticArenaBaseline baseline;
    @Autowired private TacticArenaProcesses childProcesses;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TacticService tacticService;

    @Value("${tactic-arena.parallel-runs:4}") private int parallelRuns;
    @Value("${tactic-arena.child.run-dir:}") private String childRunDir;
    @Value("${tactic-arena.child.baseline-dir:}") private String childBaselineDir;
    @Value("${match2d.mode:off}") private String match2dMode;

    private final Object lock = new Object();
    private final Map<Long, Process> processes = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    /** The test whose PREPARING step started the current/last base-world build. */
    private volatile Long buildRequestedBy;

    // ================================================================ lifecycle

    private boolean childMode() {
        return (childRunDir != null && !childRunDir.isBlank()) || (childBaselineDir != null && !childBaselineDir.isBlank());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (childMode()) return;
        try {
            for (TacticArenaTest test : testRepository.findAll()) {
                if (terminal(test.getStatus())) continue;
                for (TacticArenaRun run : runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId())) {
                    if (terminal(run.getStatus())) continue;
                    run.setStatus(FAILED);
                    run.setMessage("The backend restarted before this run finished");
                    run.setFinishedAt(Instant.now());
                    runRepository.save(run);
                }
                test.setStatus(FAILED);
                test.setMessage("The backend restarted before the test finished");
                test.setFinishedAt(Instant.now());
                testRepository.save(test);
                deleteQuietly(testWorkDir(test.getId()));
            }
            baseline.cleanupAfterRestart();
            exporter.importMissing();
        } catch (Exception e) {
            log.warn("[tactic-arena] startup recovery failed: {}", e.toString());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tactic-arena-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                log.warn("[tactic-arena] scheduler tick failed", t);
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        processes.values().forEach(TacticArenaProcesses::kill);
        baseline.stop();
    }

    // ================================================================ base world

    public Map<String, Object> baselineStatus() {
        return baseline.toJson();
    }

    public Map<String, Object> rebuildBaseline() {
        if (childMode()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not available in a simulation process");
        synchronized (lock) {
            boolean busy = testRepository.findAll().stream()
                    .anyMatch(t -> RUNNING.equals(t.getStatus()) || PREPARING.equals(t.getStatus()));
            if (busy) throw new ResponseStatusException(HttpStatus.CONFLICT, "A test is running; rebuild the base world after it ends");
            if (baseline.building()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The base world is already being generated");
            buildRequestedBy = null;
            baseline.rebuild();
            return baseline.toJson();
        }
    }

    /** 409 with the base world's state when the launch page cannot be served yet. */
    public ResponseStatusException baselineMissing() {
        String status = baseline.status();
        String message = switch (status) {
            case TacticArenaBaseline.BUILDING -> "The base world is being generated";
            case TacticArenaBaseline.FAILED -> "The base world could not be generated: " + baseline.lastError();
            default -> "There is no base world yet; generate it with POST /tactic-arena/baseline/rebuild";
        };
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public JsonNode clubs() {
        if (!baseline.ready()) throw baselineMissing();
        return baseline.clubs();
    }

    public JsonNode setup(long teamId) {
        if (!baseline.ready()) throw baselineMissing();
        JsonNode setup = baseline.setup(teamId);
        if (setup == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such club in the base world");
        return setup;
    }

    // ================================================================ create

    public record CreateRequest(String name, Long teamId, PersonalizedTacticView tactic, String owner, Boolean autoEleven) {}

    /** Team instructions: allowed values, the first-listed default being the game's default. */
    static final Map<String, List<String>> INSTRUCTION_VALUES = new LinkedHashMap<>();
    static {
        INSTRUCTION_VALUES.put("mentality", List.of("Balanced", "Very Defensive", "Defensive", "Attacking", "Very Attacking"));
        INSTRUCTION_VALUES.put("inPossession", List.of("Standard", "Keep Ball", "Free Ball Early"));
        INSTRUCTION_VALUES.put("passingType", List.of("Normal", "Short", "Long"));
        INSTRUCTION_VALUES.put("tempo", List.of("Standard", "Much Lower", "Lower", "Higher", "Much Higher"));
        INSTRUCTION_VALUES.put("timeWasting", List.of("Sometimes", "Never", "Frequently", "Always"));
        INSTRUCTION_VALUES.put("defensiveLine", List.of("Standard", "Deep", "High"));
        INSTRUCTION_VALUES.put("pressing", List.of("Normal", "Very Easy", "Easy", "Aggressive", "Very Aggressive"));
        INSTRUCTION_VALUES.put("width", List.of("Balanced", "Narrow", "Wide"));
        INSTRUCTION_VALUES.put("dribbling", List.of("Standard", "Less", "More"));
        INSTRUCTION_VALUES.put("foulFrequency", List.of("Normal", "Rarely", "Often"));
        INSTRUCTION_VALUES.put("foulHardness", List.of("Medium", "Soft", "Hard"));
        INSTRUCTION_VALUES.put("tempoFragmentation", List.of("Normal", "Flowing", "Fragment"));
        INSTRUCTION_VALUES.put("widePlay", List.of("Shoot", "Cut Inside", "Cross"));
        INSTRUCTION_VALUES.put("transition", List.of("Balanced", "Win Fouls", "Fast Counter"));
        INSTRUCTION_VALUES.put("recovery", List.of("Standard", "Slow", "Very Fast", "Instantly"));
    }

    /** autoEleven: missing instructions take the game's default; given ones must be allowed values. */
    private PersonalizedTacticView normalizeAuto(PersonalizedTacticView tactic) {
        Map<String, Object> values = objectMapper.convertValue(tactic, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        for (Map.Entry<String, List<String>> entry : INSTRUCTION_VALUES.entrySet()) {
            Object value = values.get(entry.getKey());
            if (value == null || value.toString().isBlank()) {
                values.put(entry.getKey(), entry.getValue().get(0));
            } else if (!entry.getValue().contains(value.toString())) {
                throw bad(entry.getKey() + " must be one of " + entry.getValue());
            }
        }
        values.put("formationDataList", List.of());
        values.put("penaltyTakerId", null);
        values.put("freeKickTakerId", null);
        values.put("cornerTakerLeftId", null);
        values.put("cornerTakerRightId", null);
        return objectMapper.convertValue(values, PersonalizedTacticView.class);
    }

    public long create(CreateRequest request, String owner) {
        if (childMode()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not available in a simulation process");
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 80) throw bad("The test name must have 1 to 80 characters");
        if (request.teamId() == null) throw bad("teamId is required");
        PersonalizedTacticView tactic = request.tactic();
        if (tactic == null) throw bad("tactic is required");
        validateFormation(tactic);
        boolean auto = Boolean.TRUE.equals(request.autoEleven());
        if (auto) tactic = normalizeAuto(tactic);
        tactic.setTeamId(request.teamId());

        synchronized (lock) {
            TacticArenaTest test = new TacticArenaTest();
            test.setUid(UUID.randomUUID().toString());
            test.setName(name);
            test.setOwner(owner);
            test.setFormation(tactic.getTactic());
            test.setTeamId(request.teamId());
            test.setSeason(1);
            test.setEngine("off".equalsIgnoreCase(match2dMode.trim()) ? "classic" : "2d-human");
            test.setStatus(QUEUED);
            test.setCreatedAt(Instant.now());
            test.setTacticJson(json(tactic));
            test.setAutoEleven(auto);

            List<TacticArenaRun> runs = new ArrayList<>();
            String[] kinds = {KIND_CHOSEN, KIND_TOP, KIND_MEDIAN, KIND_BOTTOM};
            for (int i = 0; i < 4; i++) {
                TacticArenaRun run = new TacticArenaRun();
                run.setRunIndex(i);
                run.setKind(kinds[i]);
                run.setStatus(QUEUED);
                runs.add(run);
            }
            runs.get(0).setTeamId(request.teamId());
            // With a base world the test is bound (and validated) now; otherwise when the
            // base world it starts with is ready.
            if (baseline.ready()) bind(test, runs);
            test = testRepository.save(test);
            for (TacticArenaRun run : runs) {
                run.setTestId(test.getId());
                runRepository.save(run);
            }
            return test.getId();
        }
    }

    /** Fill club, league, season span and reference clubs from the base world; validate the eleven. */
    private void bind(TacticArenaTest test, List<TacticArenaRun> runs) {
        JsonNode meta = baseline.meta();
        JsonNode league = baseline.leagueOf(test.getTeamId());
        JsonNode setup = baseline.setup(test.getTeamId());
        if (meta == null || league == null || setup == null) throw bad("Club " + test.getTeamId() + " is not in the base world");
        PersonalizedTacticView tactic;
        try {
            tactic = objectMapper.readValue(test.getTacticJson(), PersonalizedTacticView.class);
        } catch (Exception e) {
            throw bad("Unreadable tactic");
        }
        Set<Long> squad = new HashSet<>();
        for (JsonNode p : setup.path("players")) {
            if (!p.path("isRetired").asBoolean(false)) squad.add(p.path("id").asLong());
        }
        if (!Boolean.TRUE.equals(test.getAutoEleven())) validateLineup(tactic, squad);

        test.setBaselineUid(meta.path("uid").asText());
        test.setTeamName(setup.path("team").path("name").asText());
        test.setLeagueId(league.path("competitionId").asLong());
        test.setLeagueName(league.path("name").asText());
        test.setSeason(meta.path("season").asInt(1));
        test.setStartDay(meta.path("startDay").asInt(1));
        test.setEndDay(meta.path("endDay").asInt());
        test.setLeagueMatchesTotal(baseline.leagueMatches(test.getTeamId()));
        test.setLeagueMatchesRemaining(test.getLeagueMatchesTotal());

        List<JsonNode> teams = new ArrayList<>();
        league.path("teams").forEach(teams::add); // highest reputation first
        JsonNode chosen = teams.stream().filter(t -> t.path("teamId").asLong() == test.getTeamId()).findFirst().orElseThrow();
        JsonNode[] clubs = {chosen, teams.get(0), teams.get((teams.size() - 1) / 2), teams.get(teams.size() - 1)};
        int totalDays = test.getEndDay() - test.getStartDay() + 1;
        for (int i = 0; i < 4; i++) {
            TacticArenaRun run = runs.get(i);
            run.setTeamId(clubs[i].path("teamId").asLong());
            run.setTeamName(clubs[i].path("name").asText());
            run.setTeamReputation(clubs[i].path("reputation").asInt());
            run.setTotalDays(totalDays);
            run.setCurrentDay(test.getStartDay());
        }
    }

    private void validateFormation(PersonalizedTacticView tactic) {
        try {
            if (tactic.getTactic() == null || tacticService.getFormationGridIndices(tactic.getTactic()).length != 11) {
                throw bad("Unknown formation " + tactic.getTactic());
            }
        } catch (RuntimeException e) {
            if (e instanceof ResponseStatusException) throw e;
            throw bad("Unknown formation " + tactic.getTactic());
        }
    }

    private static void validateLineup(PersonalizedTacticView tactic, Set<Long> squad) {
        List<FormationData> list = tactic.getFormationDataList() == null ? List.of() : tactic.getFormationDataList();
        Set<Integer> slots = new HashSet<>();
        Set<Long> players = new HashSet<>();
        int starters = 0;
        for (FormationData fd : list) {
            if (fd == null || fd.getPlayerId() <= 0) throw bad("The line-up contains an empty entry");
            if (fd.getPositionIndex() < 0 || fd.getPositionIndex() > 36) throw bad("Slot out of range: " + fd.getPositionIndex());
            if (!slots.add(fd.getPositionIndex())) throw bad("Duplicate slot " + fd.getPositionIndex());
            if (!players.add(fd.getPlayerId())) throw bad("A player appears twice in the line-up");
            if (fd.getPositionIndex() < 30) starters++;
        }
        if (starters != 11) throw bad("The starting eleven must have 11 players (has " + starters + ")");
        if (!squad.containsAll(players)) throw bad("Every selected player must belong to the club in the base world");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ================================================================ scheduler

    /**
     * One scheduler step. Tests are served in launch order; the runs of all of them share
     * {@code tactic-arena.parallel-runs} simulation processes. A test stays QUEUED until its
     * first run gets a free slot.
     */
    void tick() {
        synchronized (lock) {
            baseline.poll();
            // 1. Running tests: progress, finished runs, finished tests.
            int running = 0;
            for (TacticArenaTest test : testRepository.findAllByStatusOrderByIdAsc(RUNNING)) {
                List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
                for (TacticArenaRun run : runs) {
                    if (RUNNING.equals(run.getStatus())) {
                        pollRun(test, run);
                        if (RUNNING.equals(run.getStatus())) running++;
                    }
                }
                if (runs.stream().allMatch(r -> terminal(r.getStatus()))) finishTest(test, runs);
            }
            // 2. Free slots go to the waiting runs in launch order. Only the tests needed to
            //    fill the slots are looked at, so a long queue costs one id-ordered query.
            int limit = Math.max(1, parallelRuns);
            if (running >= limit) return;
            for (TacticArenaTest test : testRepository.findTop50ByStatusInOrderByIdAsc(List.of(RUNNING, PREPARING, QUEUED))) {
                if (running >= limit) break;
                if (!RUNNING.equals(test.getStatus()) && !prepare(test)) {
                    if (!baseline.ready()) break; // every later test waits for the same base world
                    continue;
                }
                List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
                for (TacticArenaRun run : runs) {
                    if (running >= limit) break;
                    if (!QUEUED.equals(run.getStatus())) continue;
                    launchRun(test, run);
                    if (RUNNING.equals(run.getStatus())) {
                        running++;
                        if (!RUNNING.equals(test.getStatus())) {
                            test.setStatus(RUNNING);
                            test.setMessage(null);
                            testRepository.save(test);
                        }
                    }
                }
                if (runs.stream().allMatch(r -> terminal(r.getStatus()))) finishTest(test, runs);
            }
        }
    }

    /** Make sure the base world exists and the test is bound to it; true when its runs may start. */
    private boolean prepare(TacticArenaTest test) {
        if (!baseline.ready()) {
            if (!PREPARING.equals(test.getStatus())) {
                test.setStatus(PREPARING);
                test.setMessage("Generating the base world (first time only)");
                testRepository.save(test);
            }
            if (baseline.building()) return false;
            if (TacticArenaBaseline.FAILED.equals(baseline.status()) && Objects.equals(buildRequestedBy, test.getId())) {
                failTest(test, baseline.lastError());
                return false;
            }
            buildRequestedBy = test.getId();
            baseline.startBuild();
            return false;
        }
        if (test.getBaselineUid() == null) {
            List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
            try {
                bind(test, runs);
                runs.forEach(runRepository::save);
            } catch (ResponseStatusException e) {
                failTest(test, e.getReason());
                return false;
            }
        } else if (!test.getBaselineUid().equals(baseline.uid())) {
            failTest(test, "The base world was generated again after this test was created; its players no longer exist");
            return false;
        }
        if (PREPARING.equals(test.getStatus())) {
            test.setStatus(QUEUED);
            test.setMessage(null);
        }
        testRepository.save(test);
        return true;
    }

    private void launchRun(TacticArenaTest test, TacticArenaRun run) {
        Path runDir = testWorkDir(test.getId()).resolve("run-" + run.getRunIndex());
        try {
            deleteQuietly(runDir);
            Path dbDir = runDir.resolve("db");
            Files.createDirectories(dbDir);
            String dbName = unzip(baseline.worldZip(), dbDir);

            PersonalizedTacticView tactic = objectMapper.readValue(test.getTacticJson(), PersonalizedTacticView.class);
            TacticArenaChildRunner.ChildSpec spec = new TacticArenaChildRunner.ChildSpec(run.getTeamId(), tactic,
                    run.getRunIndex() != 0 || Boolean.TRUE.equals(test.getAutoEleven()), test.getSeason(), test.getStartDay(), test.getEndDay(), test.getEngine());
            Files.write(runDir.resolve("spec.json"), objectMapper.writeValueAsBytes(spec));

            Process process = childProcesses.start(runDir, dbDir, dbName, Map.of(
                    "match2d.mode", "classic".equals(test.getEngine()) ? "off" : "human",
                    "tactic-arena.child.run-dir", runDir.toAbsolutePath().toString()));
            processes.put(run.getId(), process);
            run.setStatus(RUNNING);
            run.setStartedAt(Instant.now());
            run.setMessage(null);
            runRepository.save(run);
            log.info("[tactic-arena] test {} run {} ({}) started, pid {}, log {}", test.getId(), run.getRunIndex(),
                    run.getTeamName(), process.pid(), runDir.resolve("child.log"));
        } catch (Exception e) {
            run.setStatus(FAILED);
            run.setMessage("Could not start the simulation: " + e.getMessage());
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        }
    }

    private void pollRun(TacticArenaTest test, TacticArenaRun run) {
        Path runDir = testWorkDir(test.getId()).resolve("run-" + run.getRunIndex());
        Process process = processes.get(run.getId());
        updateProgress(test, run, runDir);
        if (process != null && process.isAlive()) {
            runRepository.save(run);
            return;
        }
        processes.remove(run.getId());
        Path resultFile = runDir.resolve("result.json.gz");
        int exit = process == null ? -1 : process.exitValue();
        run.setFinishedAt(Instant.now());
        try {
            if (exit == 0 && Files.exists(resultFile)) {
                byte[] gz = Files.readAllBytes(resultFile);
                JsonNode result = objectMapper.readTree(TacticArenaSupport.gunzip(gz));
                TacticArenaRunResult stored = new TacticArenaRunResult();
                stored.setRunId(run.getId());
                stored.setTestId(test.getId());
                stored.setGzJson(gz);
                resultRepository.save(stored);
                run.setTotalsJson(json(totals(result)));
                run.setStatus(DONE);
                run.setCurrentDay(test.getEndDay() + 1);
                run.setSimulatedDays(run.getTotalDays());
                run.setMessage(null);
                deleteQuietly(runDir);
            } else {
                run.setStatus(FAILED);
                run.setMessage(failureMessage(runDir, exit));
                deleteQuietly(runDir.resolve("db"));
            }
        } catch (Exception e) {
            run.setStatus(FAILED);
            run.setMessage("Could not read the run result: " + e.getMessage());
        }
        runRepository.save(run);
    }

    private void updateProgress(TacticArenaTest test, TacticArenaRun run, Path runDir) {
        Path progress = runDir.resolve("progress.json");
        if (!Files.exists(progress)) return;
        try {
            JsonNode node = objectMapper.readTree(Files.readString(progress));
            int day = node.path("day").asInt(test.getStartDay());
            if (node.path("season").asInt(test.getSeason()) != test.getSeason()) day = test.getEndDay() + 1;
            run.setCurrentDay(day);
            run.setSimulatedDays(Math.max(0, Math.min(run.getTotalDays(), day - test.getStartDay())));
        } catch (Exception ignored) {
            // being rewritten; next tick
        }
    }

    private static String failureMessage(Path runDir, int exit) {
        try {
            Path error = runDir.resolve("error.txt");
            if (Files.exists(error)) return "Simulation failed: " + Files.readString(error, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // fall through
        }
        String tail = "";
        try {
            List<String> lines = Files.readAllLines(runDir.resolve("child.log"), StandardCharsets.UTF_8);
            tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
        } catch (Exception ignored) {
            // no log
        }
        String message = "The simulation process stopped (exit code " + exit + ")" + (tail.isEmpty() ? "" : ": " + tail);
        return message.length() > 3900 ? message.substring(0, 3900) : message;
    }

    private void finishTest(TacticArenaTest test, List<TacticArenaRun> runs) {
        boolean allDone = runs.stream().allMatch(r -> DONE.equals(r.getStatus()));
        if (allDone) {
            test.setStatus(DONE);
            test.setMessage(null);
        } else if (!CANCELLED.equals(test.getStatus())) {
            test.setStatus(FAILED);
            test.setMessage(runs.stream().filter(r -> FAILED.equals(r.getStatus())).findFirst()
                    .map(r -> "Run " + r.getRunIndex() + " (" + r.getTeamName() + "): " + r.getMessage())
                    .orElse("A run did not finish"));
        }
        test.setFinishedAt(Instant.now());
        testRepository.save(test);
        if (allDone) {
            deleteQuietly(testWorkDir(test.getId()));
            try {
                exporter.exportTest(test.getId());
            } catch (Exception e) {
                log.warn("[tactic-arena] export of test {} failed: {}", test.getId(), e.toString());
            }
        }
    }

    private void failTest(TacticArenaTest test, String message) {
        for (TacticArenaRun run : runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId())) {
            if (terminal(run.getStatus())) continue;
            run.setStatus(FAILED);
            run.setMessage(message);
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        }
        test.setStatus(FAILED);
        test.setMessage(message);
        test.setFinishedAt(Instant.now());
        testRepository.save(test);
        deleteQuietly(testWorkDir(test.getId()));
    }

    // ================================================================ cancel / delete

    public Map<String, Object> cancel(long id) {
        synchronized (lock) {
            TacticArenaTest test = require(id);
            if (!terminal(test.getStatus())) {
                for (TacticArenaRun run : runRepository.findAllByTestIdOrderByRunIndexAsc(id)) {
                    if (terminal(run.getStatus())) continue;
                    Process process = processes.remove(run.getId());
                    if (process != null) TacticArenaProcesses.kill(process);
                    run.setStatus(CANCELLED);
                    run.setMessage("Cancelled");
                    run.setFinishedAt(Instant.now());
                    runRepository.save(run);
                }
                test.setStatus(CANCELLED);
                test.setMessage("Cancelled");
                test.setFinishedAt(Instant.now());
                testRepository.save(test);
                deleteLater(testWorkDir(id));
                // A base world being generated is kept going: the next test needs it too.
            }
            return summary(test, runRepository.findAllByTestIdOrderByRunIndexAsc(id));
        }
    }

    public void delete(long id) {
        cancel(id);
        synchronized (lock) {
            imageRepository.deleteAllByTestId(id);
            resultRepository.deleteAllByTestId(id);
            runRepository.deleteAllByTestId(id);
            testRepository.deleteById(id);
        }
    }

    /** Killed processes release their H2 files a moment later. */
    private void deleteLater(Path dir) {
        if (scheduler == null) return;
        scheduler.schedule(() -> deleteQuietly(dir), 5, TimeUnit.SECONDS);
    }

    // ================================================================ images

    public void putImage(long testId, long runId, String kind, String contentType, byte[] data) {
        TacticArenaRun run = requireRun(testId, runId);
        if (!TacticArenaSupport.IMAGE_KINDS.contains(kind)) throw bad("Unknown image kind " + kind);
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!type.equals("image/webp") && !type.equals("image/png")) throw bad("Content-Type must be image/webp or image/png");
        if (data == null || data.length == 0) throw bad("Empty image");
        if (data.length > MAX_IMAGE_BYTES) throw bad("Image larger than 8 MB");
        TacticArenaImage image = imageRepository.findByRunIdAndKind(run.getId(), kind).orElseGet(TacticArenaImage::new);
        image.setTestId(testId);
        image.setRunId(run.getId());
        image.setKind(kind);
        image.setContentType(type);
        image.setData(data);
        image.setCreatedAt(Instant.now());
        imageRepository.save(image);
        try {
            exporter.exportImage(testId, run.getRunIndex(), kind, type, data);
        } catch (Exception e) {
            log.warn("[tactic-arena] export of an image of test {} failed: {}", testId, e.toString());
        }
    }

    public TacticArenaImage image(long testId, long runId, String kind) {
        requireRun(testId, runId);
        return imageRepository.findByRunIdAndKind(runId, kind)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image"));
    }

    // ================================================================ read models

    public Map<String, Object> list() {
        if (!childMode()) {
            try {
                exporter.importMissingThrottled();
            } catch (Exception e) {
                log.warn("[tactic-arena] import from the export folder failed: {}", e.toString());
            }
        }
        List<TacticArenaTest> tests = testRepository.findAllByOrderByIdDesc();
        Map<Long, List<TacticArenaRun>> runsByTest = tests.isEmpty() ? Map.of() : runRepository
                .findAllByTestIdIn(tests.stream().map(TacticArenaTest::getId).toList()).stream()
                .collect(Collectors.groupingBy(TacticArenaRun::getTestId));
        List<Map<String, Object>> rows = new ArrayList<>();
        int done = 0;
        long matches = 0;
        for (TacticArenaTest test : tests) {
            List<TacticArenaRun> runs = new ArrayList<>(runsByTest.getOrDefault(test.getId(), List.of()));
            runs.sort(Comparator.comparingInt(TacticArenaRun::getRunIndex));
            Map<String, Object> row = summary(test, runs);
            rows.add(row);
            if (DONE.equals(test.getStatus())) {
                done++;
                if (row.get("results") instanceof TacticArenaStats.Results r) matches += r.overall().pld();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testsCount", done);
        body.put("matchesPlayed", matches);
        body.put("gitPush", gitPublisher.toJson());
        body.put("baseline", baseline.toJson());
        body.put("tests", rows);
        return body;
    }

    public Map<String, Object> detail(long id) {
        return detail(require(id), false);
    }

    Map<String, Object> detail(TacticArenaTest test, boolean forExport) {
        List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
        Map<String, Object> body = summary(test, runs);
        body.put("message", test.getMessage());
        body.put("tactic", readTree(test.getTacticJson()));
        body.put("leagueMatchesTotal", test.getLeagueMatchesTotal());
        body.put("leagueMatchesRemaining", test.getLeagueMatchesRemaining());
        body.put("baselineUid", test.getBaselineUid());
        if (!forExport) body.put("gitPush", gitPublisher.toJson());
        Map<Long, List<String>> images = new HashMap<>();
        for (Object[] row : imageRepository.kindsForTest(test.getId())) {
            images.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        List<Map<String, Object>> runRows = new ArrayList<>();
        for (TacticArenaRun run : runs) {
            Map<String, Object> r = runSummary(run);
            List<String> kinds = images.getOrDefault(run.getId(), List.of());
            r.put("images", TacticArenaSupport.IMAGE_KINDS.stream().filter(kinds::contains).toList());
            TacticArenaStats.RunTotals totals = readTotals(run);
            r.put("stats", totals == null ? null : TacticArenaStats.average(List.of(totals)));
            r.put("result", DONE.equals(run.getStatus())
                    ? resultRepository.findById(run.getId()).map(res -> readTree(TacticArenaSupport.gunzip(res.getGzJson()))).orElse(null)
                    : null);
            runRows.add(r);
        }
        body.put("runs", runRows);
        return body;
    }

    Map<String, Object> summary(TacticArenaTest test, List<TacticArenaRun> runs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", test.getId());
        row.put("uid", test.getUid());
        row.put("name", test.getName());
        row.put("formation", test.getFormation());
        row.put("owner", test.getOwner());
        row.put("teamId", test.getTeamId());
        row.put("teamName", test.getTeamName());
        row.put("leagueId", test.getLeagueId());
        row.put("leagueName", test.getLeagueName());
        row.put("season", test.getSeason());
        row.put("startDay", test.getStartDay());
        row.put("endDay", test.getEndDay());
        row.put("engine", test.getEngine());
        row.put("autoEleven", Boolean.TRUE.equals(test.getAutoEleven()));
        row.put("createdAt", test.getCreatedAt() == null ? null : test.getCreatedAt().toString());
        row.put("finishedAt", test.getFinishedAt() == null ? null : test.getFinishedAt().toString());
        row.put("status", test.getStatus());
        double progress = PREPARING.equals(test.getStatus()) ? baseline.progress()
                : runs.isEmpty() ? 0 : runs.stream().mapToDouble(TacticArenaService::runProgress).average().orElse(0);
        row.put("progress", Math.round(progress * 10) / 10.0);
        List<TacticArenaStats.RunTotals> reference = new ArrayList<>();
        TacticArenaStats.RunTotals chosen = null;
        for (TacticArenaRun run : runs) {
            TacticArenaStats.RunTotals totals = readTotals(run);
            if (totals == null) continue;
            if (run.getRunIndex() == 0) chosen = totals;
            else reference.add(totals);
        }
        row.put("results", reference.size() == 3 ? TacticArenaStats.average(reference) : null);
        row.put("chosen", chosen == null ? null : TacticArenaStats.average(List.of(chosen)));
        return row;
    }

    private Map<String, Object> runSummary(TacticArenaRun run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", run.getId());
        r.put("index", run.getRunIndex());
        r.put("kind", run.getKind());
        r.put("teamId", run.getTeamId());
        r.put("teamName", run.getTeamName());
        r.put("teamReputation", run.getTeamReputation());
        r.put("status", run.getStatus());
        r.put("progress", Math.round(runProgress(run) * 10) / 10.0);
        r.put("currentDay", run.getCurrentDay());
        r.put("simulatedDays", run.getSimulatedDays());
        r.put("totalDays", run.getTotalDays());
        r.put("message", run.getMessage());
        r.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        r.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        return r;
    }

    private static double runProgress(TacticArenaRun run) {
        if (DONE.equals(run.getStatus())) return 100;
        if (run.getTotalDays() <= 0) return 0;
        return Math.min(99.9, run.getSimulatedDays() * 100.0 / run.getTotalDays());
    }

    // ================================================================ totals

    /** League matches simulated in the run, from the collected result. */
    TacticArenaStats.RunTotals totals(JsonNode result) {
        List<TacticArenaStats.MatchLine> lines = new ArrayList<>();
        long teamId = result.path("teamId").asLong();
        for (JsonNode m : result.path("matches")) {
            if (!"League".equals(m.path("competitionType").asText())) continue;
            if (!m.path("inRun").asBoolean(true)) continue;
            if (!"played".equals(m.path("status").asText())) continue;
            if (m.path("teamGoals").isNull() || m.path("teamGoals").isMissingNode()) continue;
            boolean home = m.path("teamId1").asLong() == teamId;
            Double xgFor = m.path("xgFor").isNumber() ? m.path("xgFor").asDouble() : null;
            Double xgAgainst = m.path("xgAgainst").isNumber() ? m.path("xgAgainst").asDouble() : null;
            lines.add(new TacticArenaStats.MatchLine(home, m.path("teamGoals").asInt(), m.path("opponentGoals").asInt(),
                    xgFor, xgAgainst));
        }
        return TacticArenaStats.totals(lines);
    }

    private TacticArenaStats.RunTotals readTotals(TacticArenaRun run) {
        if (!DONE.equals(run.getStatus()) || run.getTotalsJson() == null) return null;
        try {
            return objectMapper.readValue(run.getTotalsJson(), TacticArenaStats.RunTotals.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ helpers

    TacticArenaTest require(long id) {
        return testRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such test"));
    }

    private TacticArenaRun requireRun(long testId, long runId) {
        require(testId);
        TacticArenaRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        if (run.getTestId() != testId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run");
        return run;
    }

    private Path testWorkDir(long testId) {
        return childProcesses.workDir().resolve("test-" + testId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readTree(byte[] json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Unpack an H2 backup; returns the database name (file name without {@code .mv.db}). */
    private static String unzip(Path zip, Path target) throws IOException {
        if (!Files.exists(zip)) throw new IOException("The base world " + zip + " is missing");
        String name = null;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path file = target.resolve(Path.of(entry.getName()).getFileName().toString()).normalize();
                if (!file.startsWith(target)) continue;
                Files.copy(zin, file, StandardCopyOption.REPLACE_EXISTING);
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".mv.db")) name = fileName.substring(0, fileName.length() - ".mv.db".length());
            }
        }
        if (name == null) throw new IOException("The base world backup has no .mv.db file");
        return name;
    }

    static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a file still held open by a dying process; left for the OS temp cleanup
                }
            });
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
