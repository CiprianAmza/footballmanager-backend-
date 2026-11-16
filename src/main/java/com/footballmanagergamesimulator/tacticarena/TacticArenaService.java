package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
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
 * Parent side of Tactic Arena: queue, database snapshot, child processes, progress,
 * results and the read models behind {@code /tactic-arena}.
 *
 * <p>One test is active at a time. When it starts, the current game database is copied
 * once with H2's {@code BACKUP TO}; every run gets its own unpacked copy and its own JVM
 * (same classpath, same main class) started with {@code --tactic-arena.child.run-dir}.
 * The career database is never written by a run.
 */
@Service
public class TacticArenaService {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaService.class);
    private static final Set<String> MATCH_TYPES = Set.of("MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN");
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    @Autowired private TacticArenaTestRepository testRepository;
    @Autowired private TacticArenaRunRepository runRepository;
    @Autowired private TacticArenaRunResultRepository resultRepository;
    @Autowired private TacticArenaImageRepository imageRepository;
    @Autowired private TacticArenaExporter exporter;
    @Autowired private TacticArenaGitPublisher gitPublisher;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private TacticService tacticService;
    @Autowired private DataSource dataSource;
    @Autowired private Environment environment;
    @Autowired private ApplicationArguments applicationArguments;

    @Value("${tactic-arena.parallel-runs:2}") private int parallelRuns;
    @Value("${tactic-arena.work-dir:}") private String workDirSetting;
    @Value("${tactic-arena.child-jvm-args:-Xmx3g}") private String childJvmArgs;
    @Value("${tactic-arena.child.run-dir:}") private String childRunDir;
    @Value("${match2d.mode:off}") private String match2dMode;

    private final Object lock = new Object();
    private final Map<Long, Process> processes = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    // ================================================================ lifecycle

    private boolean childMode() {
        return childRunDir != null && !childRunDir.isBlank();
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
        processes.values().forEach(TacticArenaService::kill);
    }

    // ================================================================ create

    public record CreateRequest(String name, Long teamId, PersonalizedTacticView tactic) {}

    public long create(CreateRequest request, String owner) {
        if (childMode()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not available in a simulation process");
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 80) throw bad("The test name must have 1 to 80 characters");
        if (request.teamId() == null) throw bad("teamId is required");
        PersonalizedTacticView tactic = request.tactic();
        if (tactic == null) throw bad("tactic is required");
        Team team = teamRepository.findById(request.teamId()).orElseThrow(() -> bad("Unknown club " + request.teamId()));
        validateTactic(team.getId(), tactic);
        tactic.setTeamId(team.getId());

        GameCalendar calendar = gameCalendarRepository.findTopByOrderBySeasonDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The game has no calendar yet"));
        int season = calendar.getSeason();
        int startDay = calendar.getCurrentDay();
        int endDay = calendarEventRepository.findAllBySeason(season).stream()
                .filter(e -> MATCH_TYPES.contains(e.getEventType()))
                .mapToInt(CalendarEvent::getDay).max().orElse(startDay);
        if (endDay < startDay || calendarEventRepository.findAllBySeasonAndStatus(season, "PENDING").stream()
                .noneMatch(e -> MATCH_TYPES.contains(e.getEventType()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No matches are left this season; start the test after the new season begins");
        }

        long leagueId = team.getCompetitionId();
        Competition league = competitionRepository.findById(leagueId).orElse(null);
        List<CompetitionTeamInfoMatch> leagueFixtures = fixtureRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(season), team.getId()).stream()
                .filter(f -> f.getCompetitionId() == leagueId).toList();

        List<Team> leagueTeams = new ArrayList<>(teamRepository.findAllByCompetitionId(leagueId));
        leagueTeams.sort(Comparator.comparingInt(Team::getReputation).reversed().thenComparingLong(Team::getId));
        if (leagueTeams.isEmpty()) throw bad("The club's league has no clubs");

        TacticArenaTest test = new TacticArenaTest();
        test.setUid(UUID.randomUUID().toString());
        test.setName(name);
        test.setOwner(owner);
        test.setFormation(tactic.getTactic());
        test.setTeamId(team.getId());
        test.setTeamName(team.getName());
        test.setLeagueId(leagueId);
        test.setLeagueName(league == null ? null : league.getName());
        test.setSeason(season);
        test.setStartDay(startDay);
        test.setEndDay(endDay);
        test.setLeagueMatchesTotal(leagueFixtures.size());
        test.setLeagueMatchesRemaining((int) leagueFixtures.stream().filter(f -> f.getTeam1Score() < 0).count());
        test.setEngine("off".equalsIgnoreCase(match2dMode.trim()) ? "classic" : "2d-human");
        test.setStatus(QUEUED);
        test.setCreatedAt(Instant.now());
        test.setTacticJson(json(tactic));
        test = testRepository.save(test);

        Team[] clubs = {team, leagueTeams.get(0), leagueTeams.get((leagueTeams.size() - 1) / 2),
                leagueTeams.get(leagueTeams.size() - 1)};
        String[] kinds = {KIND_CHOSEN, KIND_TOP, KIND_MEDIAN, KIND_BOTTOM};
        for (int i = 0; i < 4; i++) {
            TacticArenaRun run = new TacticArenaRun();
            run.setTestId(test.getId());
            run.setRunIndex(i);
            run.setKind(kinds[i]);
            run.setTeamId(clubs[i].getId());
            run.setTeamName(clubs[i].getName());
            run.setTeamReputation(clubs[i].getReputation());
            run.setStatus(QUEUED);
            run.setTotalDays(endDay - startDay + 1);
            run.setCurrentDay(startDay);
            runRepository.save(run);
        }
        return test.getId();
    }

    private void validateTactic(long teamId, PersonalizedTacticView tactic) {
        try {
            if (tactic.getTactic() == null || tacticService.getFormationGridIndices(tactic.getTactic()).length != 11) {
                throw bad("Unknown formation " + tactic.getTactic());
            }
        } catch (RuntimeException e) {
            if (e instanceof ResponseStatusException) throw e;
            throw bad("Unknown formation " + tactic.getTactic());
        }
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
        Set<Long> squad = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired()).map(Human::getId).collect(Collectors.toSet());
        if (!squad.containsAll(players)) throw bad("Every selected player must belong to the club");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ================================================================ scheduler

    void tick() {
        synchronized (lock) {
            TacticArenaTest active = testRepository.findAllByStatusOrderByIdAsc(RUNNING).stream().findFirst().orElse(null);
            if (active == null) {
                active = testRepository.findAllByStatusOrderByIdAsc(QUEUED).stream().findFirst().orElse(null);
                if (active == null) return;
                if (!startTest(active)) return;
            }
            List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(active.getId());
            int running = 0;
            for (TacticArenaRun run : runs) {
                if (RUNNING.equals(run.getStatus())) {
                    pollRun(active, run);
                    if (RUNNING.equals(run.getStatus())) running++;
                }
            }
            for (TacticArenaRun run : runs) {
                if (running >= Math.max(1, parallelRuns)) break;
                if (QUEUED.equals(run.getStatus())) {
                    launchRun(active, run);
                    if (RUNNING.equals(run.getStatus())) running++;
                }
            }
            if (runs.stream().allMatch(r -> terminal(r.getStatus()))) finishTest(active, runs);
        }
    }

    private boolean startTest(TacticArenaTest test) {
        test.setStatus(RUNNING);
        testRepository.save(test);
        try {
            Path dir = testWorkDir(test.getId());
            Files.createDirectories(dir);
            snapshot(dir.resolve("snapshot.zip"));
            return true;
        } catch (Exception e) {
            failTest(test, "Could not copy the game database: " + e.getMessage());
            return false;
        }
    }

    /** One consistent hot copy of the career database for the whole test. */
    private void snapshot(Path zip) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null || !url.startsWith("jdbc:h2:") || url.startsWith("jdbc:h2:mem:")) {
                throw new IllegalStateException("Tactic Arena needs the H2 file database (current: " + url + ")");
            }
            Files.deleteIfExists(zip);
            try (Statement st = connection.createStatement()) {
                st.execute("BACKUP TO '" + zip.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
    }

    private void launchRun(TacticArenaTest test, TacticArenaRun run) {
        Path runDir = testWorkDir(test.getId()).resolve("run-" + run.getRunIndex());
        try {
            deleteQuietly(runDir);
            Path dbDir = runDir.resolve("db");
            Files.createDirectories(dbDir);
            String dbName = unzip(testWorkDir(test.getId()).resolve("snapshot.zip"), dbDir);

            PersonalizedTacticView tactic = objectMapper.readValue(test.getTacticJson(), PersonalizedTacticView.class);
            TacticArenaChildRunner.ChildSpec spec = new TacticArenaChildRunner.ChildSpec(run.getTeamId(), tactic,
                    run.getRunIndex() != 0, test.getSeason(), test.getStartDay(), test.getEndDay(), test.getEngine());
            Files.write(runDir.resolve("spec.json"), objectMapper.writeValueAsBytes(spec));

            String dbUrl = "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/') + "/" + dbName;
            List<String> command = childCommand(runDir, dbUrl, test.getEngine());
            File logFile = runDir.resolve("child.log").toFile();
            ProcessBuilder pb = new ProcessBuilder(command).directory(runDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            Process process = pb.start();
            processes.put(run.getId(), process);
            run.setStatus(RUNNING);
            run.setStartedAt(Instant.now());
            run.setMessage(null);
            runRepository.save(run);
            log.info("[tactic-arena] test {} run {} ({}) started, pid {}, log {}", test.getId(), run.getRunIndex(),
                    run.getTeamName(), process.pid(), logFile);
        } catch (Exception e) {
            run.setStatus(FAILED);
            run.setMessage("Could not start the simulation: " + e.getMessage());
            run.setFinishedAt(Instant.now());
            runRepository.save(run);
        }
    }

    private List<String> childCommand(Path runDir, String dbUrl, String engine) {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> command = new ArrayList<>();
        command.add(java);
        for (String arg : childJvmArgs.trim().split("\\s+")) if (!arg.isBlank()) command.add(arg);
        command.add("-Dfile.encoding=UTF-8");
        String classpath = System.getProperty("java.class.path");
        boolean singleJar = classpath != null && !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar");
        if (singleJar) {
            command.add("-jar");
            command.add(classpath);
        } else {
            command.add("-cp");
            command.add(classpath);
            command.add("com.footballmanagergamesimulator.Main");
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("server.port", "0");
        overrides.put("spring.datasource.url", dbUrl);
        overrides.put("spring.datasource.username", environment.getProperty("spring.datasource.username", "sa"));
        overrides.put("spring.datasource.password", environment.getProperty("spring.datasource.password", ""));
        overrides.put("match2d.mode", "classic".equals(engine) ? "off" : "human");
        overrides.put("facelab.enabled", "false");
        overrides.put("game.checkpoint.enabled", "false");
        overrides.put("tactic-arena.export-dir", "");
        overrides.put("tactic-arena.git-push", "false");
        overrides.put("tactic-arena.child.run-dir", runDir.toAbsolutePath().toString());
        overrides.put("tactic-arena.child.parent-pid", String.valueOf(ProcessHandle.current().pid()));
        for (String arg : applicationArguments.getSourceArgs()) {
            String key = arg.startsWith("--") ? arg.substring(2).split("=", 2)[0] : null;
            if (key != null && overrides.containsKey(key)) continue;
            command.add(arg);
        }
        overrides.forEach((key, value) -> command.add("--" + key + "=" + value));
        return command;
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
        deleteQuietly(testWorkDir(test.getId()).resolve("snapshot.zip"));
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
                    if (process != null) kill(process);
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

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
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
        List<TacticArenaTest> tests = testRepository.findAllByOrderByIdDesc();
        Map<Long, List<TacticArenaRun>> runsByTest = runRepository
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
        body.put("tests", rows);
        return body;
    }

    public Map<String, Object> detail(long id) {
        return detail(require(id), false);
    }

    /** {@code withResults=false} is the API (results inline too); both include the run results. */
    Map<String, Object> detail(TacticArenaTest test, boolean forExport) {
        List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
        Map<String, Object> body = summary(test, runs);
        body.put("message", test.getMessage());
        body.put("tactic", readTree(test.getTacticJson()));
        body.put("leagueMatchesTotal", test.getLeagueMatchesTotal());
        body.put("leagueMatchesRemaining", test.getLeagueMatchesRemaining());
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
        row.put("createdAt", test.getCreatedAt() == null ? null : test.getCreatedAt().toString());
        row.put("finishedAt", test.getFinishedAt() == null ? null : test.getFinishedAt().toString());
        row.put("status", test.getStatus());
        double progress = runs.isEmpty() ? 0 : runs.stream().mapToDouble(TacticArenaService::runProgress).average().orElse(0);
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

    Path workDir() {
        if (workDirSetting != null && !workDirSetting.isBlank()) return Path.of(workDirSetting);
        return Path.of(System.getProperty("java.io.tmpdir"), "fm-tactic-arena",
                environment.getProperty("server.port", "default"));
    }

    private Path testWorkDir(long testId) {
        return workDir().resolve("test-" + testId);
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

    /** Unpack the H2 backup; returns the database name (file name without {@code .mv.db}). */
    private static String unzip(Path zip, Path target) throws IOException {
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
        if (name == null) throw new IOException("The database backup has no .mv.db file");
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

    private static File nullFile() {
        return new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }

    @SuppressWarnings("unused")
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
}
