package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import com.footballmanagergamesimulator.user.CareerOnboardingService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.ManagerSetupRequest;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Child-process side of the base world. Active only when the backend is started with
 * {@code --tactic-arena.child.baseline-dir=<dir>} on an EMPTY database: the game has then
 * generated a new world at startup. This starts a career the way a player does (a test
 * user takes over a club through {@link CareerOnboardingService#setupManager}), makes sure
 * the season 1 calendar exists, takes an H2 backup before any match ({@code world.zip}),
 * then writes the club list and every club's tactics-page data for the launch page
 * ({@code clubs.json}, {@code setup/<teamId>.json.gz}) and {@code meta.json}.
 */
@Component
@ConditionalOnProperty(name = "tactic-arena.child.baseline-dir")
public class TacticArenaBaselineBuilder {

    private static final Set<String> MATCH_TYPES = Set.of("MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN");

    @Value("${tactic-arena.child.baseline-dir}") private String outDir;
    @Value("${tactic-arena.child.parent-pid:0}") private long parentPid;
    /** Refresh only {@code clubs.json} of an existing base world (this database is a copy of it). */
    @Value("${tactic-arena.child.refresh:false}") private boolean refreshOnly;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private CalendarService calendarService;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CareerOnboardingService onboardingService;
    @Autowired private TacticArenaWorld world;
    @Autowired private DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        TacticArenaSupport.startParentWatchdog(parentPid);
        Thread worker = new Thread(this::buildAndExit, "tactic-arena-baseline");
        worker.setDaemon(false);
        worker.start();
    }

    private void buildAndExit() {
        Path dir = Path.of(outDir);
        int code = 0;
        try {
            build(dir);
            System.out.println("[tactic-arena] base world written to " + dir);
        } catch (Throwable t) {
            code = 3;
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            System.err.println("[tactic-arena] base world failed: " + trace);
            try {
                Files.writeString(dir.resolve("error.txt"),
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // the parent falls back to the exit code
            }
        }
        System.exit(code);
    }

    void build(Path dir) throws Exception {
        if (refreshOnly) {
            Files.createDirectories(dir);
            progress(dir, "CLUBS", 0, 0);
            Files.write(dir.resolve("clubs.json"), objectMapper.writeValueAsBytes(world.clubs()));
            return;
        }
        Files.createDirectories(dir.resolve("setup"));
        progress(dir, "CAREER", 0, 0);

        // 1. The career step: calendar of season 1 and a test manager at a club.
        GameCalendar calendar = calendarService.getOrCreateCalendar(1);
        if (calendar.getSeason() != 1 || calendar.getCurrentDay() != 1) {
            throw new IllegalStateException("The new world is not at season 1, day 1 (season "
                    + calendar.getSeason() + ", day " + calendar.getCurrentDay() + ")");
        }
        startTestCareer();
        List<CalendarEvent> matchEvents = calendarEventRepository.findAllBySeason(1).stream()
                .filter(e -> MATCH_TYPES.contains(e.getEventType())).toList();
        if (matchEvents.isEmpty()) throw new IllegalStateException("The new world has no season 1 match calendar");
        if (matchEvents.stream().anyMatch(e -> !"PENDING".equals(e.getStatus()))) {
            throw new IllegalStateException("A match of the new world was already played");
        }
        int endDay = matchEvents.stream().mapToInt(CalendarEvent::getDay).max().orElse(1);

        // 2. The base world itself, before any match.
        progress(dir, "BACKUP", 0, 0);
        Path zip = dir.resolve("world.zip");
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.execute("BACKUP TO '" + zip.toAbsolutePath().toString().replace("'", "''") + "'");
        }

        // 3. What the launch page reads: clubs and each club's tactics-page data.
        Map<String, Object> clubs = world.clubs();
        Files.write(dir.resolve("clubs.json"), objectMapper.writeValueAsBytes(clubs));
        List<Team> teams = teamRepository.findAll();
        Map<String, Integer> leagueMatches = new TreeMap<>();
        for (Team team : teams) {
            long count = fixtureRepository.findAllBySeasonNumberAndTeamId("1", team.getId()).stream()
                    .filter(f -> f.getCompetitionId() == team.getCompetitionId()).count();
            leagueMatches.put(String.valueOf(team.getId()), (int) count);
        }
        AtomicInteger done = new AtomicInteger();
        progress(dir, "SETUPS", 0, teams.size());
        ForkJoinPool pool = new ForkJoinPool(4);
        try {
            pool.submit(() -> teams.parallelStream().forEach(team -> {
                try {
                    byte[] json = objectMapper.writeValueAsBytes(world.setup(team.getId()));
                    Files.write(dir.resolve("setup").resolve(team.getId() + ".json.gz"), TacticArenaSupport.gzip(json));
                } catch (Exception e) {
                    throw new IllegalStateException("setup of club " + team.getId() + ": " + e.getMessage(), e);
                }
                progress(dir, "SETUPS", done.incrementAndGet(), teams.size());
            })).get();
        } finally {
            pool.shutdown();
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("uid", UUID.randomUUID().toString());
        meta.put("createdAt", Instant.now().toString());
        meta.put("appVersion", TacticArenaSupport.appVersion());
        meta.put("season", 1);
        meta.put("startDay", calendar.getCurrentDay());
        meta.put("endDay", endDay);
        meta.put("clubs", teams.size());
        meta.put("leagueMatchesByTeam", leagueMatches);
        Path tmp = dir.resolve("meta.json.tmp");
        Files.write(tmp, objectMapper.writeValueAsBytes(meta));
        Files.move(tmp, dir.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Exactly what the career screen does, for the lowest-reputation club that accepts a manager without a licence. */
    private void startTestCareer() {
        User user = userRepository.findAll().stream().findFirst().orElseGet(() -> {
            User created = new User();
            created.setUsername("tactic-arena");
            created.setPassword("-");
            created.setEmail("tactic-arena@localhost");
            created.setActive(true);
            created.setCareerRole(CareerRole.MANAGER);
            return userRepository.save(created);
        });
        if (user.getManagerId() != null && user.getTeamId() != null) return;
        List<Team> candidates = new ArrayList<>(teamRepository.findAll());
        candidates.sort(Comparator.comparingInt(Team::getReputation).thenComparingLong(Team::getId));
        RuntimeException last = null;
        for (Team team : candidates) {
            try {
                onboardingService.setupManager(user, new ManagerSetupRequest("Tactic Arena", 40, team.getId(), false));
                return;
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw new IllegalStateException("No club accepted the test manager", last);
    }

    private static void progress(Path dir, String stage, int done, int total) {
        try {
            Path tmp = dir.resolve("progress.json." + Thread.currentThread().getId());
            Files.writeString(tmp, "{\"stage\":\"" + stage + "\",\"done\":" + done + ",\"total\":" + total + "}");
            Files.move(tmp, dir.resolve("progress.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
