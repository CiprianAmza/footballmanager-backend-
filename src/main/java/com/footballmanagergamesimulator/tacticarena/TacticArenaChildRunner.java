package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionDisplayService;
import com.footballmanagergamesimulator.service.GameAdvanceService;
import com.footballmanagergamesimulator.service.MatchService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Child-process side of a Tactic Arena run. Active only when the backend is started with
 * {@code --tactic-arena.child.run-dir=<dir>} on a private copy of the game database.
 *
 * <p>It turns the tested club into the (only) human-managed club of the copy, with
 * "always continue" on, saves the test tactic as that club's tactic and plays the game's
 * own calendar day by day until the season's last match day. Then it writes
 * {@code result.json.gz} into the run directory and exits. Progress goes to
 * {@code progress.json}; any failure to {@code error.txt} and a non-zero exit code.
 */
@Component
@ConditionalOnProperty(name = "tactic-arena.child.run-dir")
public class TacticArenaChildRunner {

    public record ChildSpec(long teamId, PersonalizedTacticView tactic, boolean autoXi,
                            int season, int startDay, int endDay, String engine) {}

    private static final Set<String> MATCH_TYPES = Set.of("MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN");

    @Value("${tactic-arena.child.run-dir}") private String runDir;
    @Value("${tactic-arena.child.parent-pid:0}") private long parentPid;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private MatchStatsRepository matchStatsRepository;
    @Autowired private MatchService matchService;
    @Autowired private CompetitionDisplayService competitionDisplayService;
    @Autowired private TacticController tacticController;
    @Autowired private GameAdvanceService gameAdvanceService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        startParentWatchdog();
        Thread worker = new Thread(this::runAndExit, "tactic-arena-child");
        worker.setDaemon(false);
        worker.start();
    }

    private void startParentWatchdog() {
        if (parentPid <= 0) return;
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                boolean alive = ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive) {
                    System.err.println("[tactic-arena] parent process " + parentPid + " is gone; exiting");
                    Runtime.getRuntime().halt(4);
                }
            }
        }, "tactic-arena-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void runAndExit() {
        Path dir = Path.of(runDir);
        int code = 0;
        try {
            ChildSpec spec = objectMapper.readValue(Files.readAllBytes(dir.resolve("spec.json")), ChildSpec.class);
            Map<String, Object> result = run(spec, dir);
            byte[] gz = TacticArenaSupport.gzip(objectMapper.writeValueAsBytes(result));
            Path tmp = dir.resolve("result.json.gz.tmp");
            Files.write(tmp, gz);
            Files.move(tmp, dir.resolve("result.json.gz"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[tactic-arena] run finished");
        } catch (Throwable t) {
            code = 3;
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            System.err.println("[tactic-arena] run failed: " + trace);
            try {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                Files.writeString(dir.resolve("error.txt"), message, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // the parent falls back to the exit code and the log
            }
        }
        System.exit(code);
    }

    Map<String, Object> run(ChildSpec spec, Path dir) throws Exception {
        long teamId = spec.teamId();
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("Club " + teamId + " is not in the database copy"));
        GameCalendar calendar = calendar();
        int season = calendar.getSeason();
        if (season != spec.season()) {
            throw new IllegalStateException("Database copy is in season " + season + ", test expects " + spec.season());
        }

        makeOnlyHumanClub(teamId);
        PersonalizedTacticView used = installTactic(team, spec);
        String first11 = objectMapper.writeValueAsString(used.getFormationDataList());
        List<PlayerView> squad = TacticArenaSupport.players(tacticController, teamId);
        Set<String> playedBefore = new HashSet<>();
        for (ScheduleView v : schedule(teamId, season)) {
            if ("played".equals(v.getStatus())) playedBefore.add(key(v));
        }

        int startDay = calendar.getCurrentDay();
        writeProgress(dir, season, startDay, calendar.getCurrentPhase());
        int stalls = 0;
        while (true) {
            calendar = calendar();
            if (calendar.getSeason() != season || calendar.getCurrentDay() > spec.endDay()) break;
            if (!matchesPending(season)) break;
            restoreTactic(teamId, used, first11);
            String before = calendar.getCurrentDay() + "/" + calendar.getCurrentPhase();
            Map<String, Object> step = gameAdvanceService.advance(season);
            if (Boolean.TRUE.equals(step.get("paused"))) {
                throw new IllegalStateException("The game paused on day " + calendar.getCurrentDay()
                        + ": " + step.getOrDefault("reason", "unknown reason"));
            }
            GameCalendar after = calendar();
            String now = after.getCurrentDay() + "/" + after.getCurrentPhase();
            if (after.getSeason() == season && now.equals(before)) {
                if (++stalls >= 3) throw new IllegalStateException("The calendar does not advance (day " + now + ")");
            } else {
                stalls = 0;
            }
            writeProgress(dir, after.getSeason(), after.getCurrentDay(), after.getCurrentPhase());
        }

        return collect(team, season, spec, used, squad, playedBefore, calendar().getCurrentDay());
    }

    // ---------------------------------------------------------------- setup

    /**
     * The copy keeps every user row (other tables may point at them) but only one of them
     * manages a club, the tested one. Every manager a user points at, and the club's own
     * manager, get "always continue" so nothing waits for input.
     */
    private void makeOnlyHumanClub(long teamId) {
        List<Human> clubManagers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        List<User> users = new ArrayList<>(userRepository.findAll());
        users.sort(Comparator.comparingInt(User::getId));
        if (users.isEmpty()) {
            User user = new User();
            user.setUsername("tactic-arena");
            user.setPassword("-");
            user.setActive(true);
            users.add(user);
        }
        Long fallbackManagerId = clubManagers.isEmpty()
                ? humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream().map(Human::getId).findFirst().orElse(null)
                : clubManagers.get(0).getId();
        Set<Long> managerIds = new HashSet<>();
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            boolean chosen = i == 0;
            user.setTeamId(chosen ? teamId : null);
            if (chosen) {
                user.setLastTeamId(teamId);
                user.setCareerRole(CareerRole.MANAGER);
            }
            user.setFired(false);
            if (user.getManagerId() == null || humanRepository.findById(user.getManagerId()).isEmpty()) {
                user.setManagerId(fallbackManagerId);
            }
            if (user.getManagerId() != null) managerIds.add(user.getManagerId());
        }
        userRepository.saveAll(users);

        List<Human> managers = new ArrayList<>(clubManagers);
        humanRepository.findAllById(managerIds).forEach(managers::add);
        for (Human manager : managers) {
            manager.setAlwaysContinue(true);
            manager.setViewFullMatch(false);
            manager.setAttendPressConferences(false);
        }
        humanRepository.saveAll(managers);
        if (!gameAdvanceService.isAlwaysContinueActive()) {
            throw new IllegalStateException("Could not switch the database copy to unattended mode");
        }
    }

    /** Save the test tactic as the club's tactic; reference clubs get the assistant's XI. */
    private PersonalizedTacticView installTactic(Team team, ChildSpec spec) throws Exception {
        PersonalizedTacticView source = spec.tactic();
        List<FormationData> xi;
        if (spec.autoXi()) {
            Map<Integer, FormationData> bySlot = new HashMap<>();
            for (FormationData fd : Optional.ofNullable(source.getFormationDataList()).orElse(List.of())) {
                if (fd != null) bySlot.put(fd.getPositionIndex(), fd);
            }
            xi = new ArrayList<>();
            for (FormationData pick : tacticController.askAssistant(team.getId(), source.getTactic())) {
                FormationData slot = bySlot.get(pick.getPositionIndex());
                if (pick.getPositionIndex() < 30 && slot != null) {
                    pick.setRole(slot.getRole());
                    pick.setDuty(slot.getDuty());
                    pick.setInstructions(slot.getInstructions());
                    pick.setSpecialRole(slot.getSpecialRole());
                    pick.setShadow(slot.isShadow());
                }
                xi.add(pick);
            }
        } else {
            xi = new ArrayList<>(Optional.ofNullable(source.getFormationDataList()).orElse(List.of()));
        }

        PersonalizedTacticView used = objectMapper.convertValue(source, PersonalizedTacticView.class);
        used.setTeamId(team.getId());
        used.setFormationDataList(xi);
        if (spec.autoXi()) {
            // The chosen club's set-piece takers are not in this squad; the game picks its own.
            used.setPenaltyTakerId(null);
            used.setFreeKickTakerId(null);
            used.setCornerTakerLeftId(null);
            used.setCornerTakerRightId(null);
        }
        restoreTactic(team.getId(), used, objectMapper.writeValueAsString(xi));
        return used;
    }

    /** Re-apply the test tactic when anything in the game changed it during the season. */
    private void restoreTactic(long teamId, PersonalizedTacticView used, String first11) {
        PersonalizedTactic tactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId)
                .orElseGet(PersonalizedTactic::new);
        boolean same = tactic.getId() != 0
                && Objects.equals(tactic.getFirst11(), first11)
                && Objects.equals(tactic.getTactic(), used.getTactic())
                && Objects.equals(tactic.getMentality(), used.getMentality())
                && Objects.equals(tactic.getPenaltyTakerId(), used.getPenaltyTakerId());
        if (same) return;
        tactic.setTeamId(teamId);
        TacticArenaSupport.copyInstructions(used, tactic);
        tactic.setFirst11(first11);
        tactic.setPenaltyTakerId(used.getPenaltyTakerId());
        tactic.setFreeKickTakerId(used.getFreeKickTakerId());
        tactic.setCornerTakerLeftId(used.getCornerTakerLeftId());
        tactic.setCornerTakerRightId(used.getCornerTakerRightId());
        personalizedTacticRepository.save(tactic);
    }

    // ---------------------------------------------------------------- loop helpers

    private GameCalendar calendar() {
        return gameCalendarRepository.findTopByOrderBySeasonDesc()
                .orElseThrow(() -> new IllegalStateException("The database copy has no calendar"));
    }

    private boolean matchesPending(int season) {
        return calendarEventRepository.findAllBySeasonAndStatus(season, "PENDING").stream()
                .anyMatch(e -> MATCH_TYPES.contains(e.getEventType()));
    }

    private void writeProgress(Path dir, int season, int day, String phase) {
        try {
            Path tmp = dir.resolve("progress.json.tmp");
            Files.writeString(tmp, "{\"season\":" + season + ",\"day\":" + day + ",\"phase\":\"" + phase + "\"}");
            Files.move(tmp, dir.resolve("progress.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // progress is best effort
        }
    }

    // ---------------------------------------------------------------- collection

    private List<ScheduleView> schedule(long teamId, int season) {
        List<ScheduleView> views = new ArrayList<>(matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(
                fixtureRepository.findAllBySeasonNumberAndTeamId(String.valueOf(season), teamId), teamId, season));
        views.sort(Comparator.comparingInt(ScheduleView::getDay).thenComparingInt(ScheduleView::getRoundNumber));
        return views;
    }

    private static String key(ScheduleView v) {
        return v.getCompetitionId() + ":" + v.getRoundNumber() + ":" + v.getTeamId1() + ":" + v.getTeamId2() + ":" + v.getLegNumber();
    }

    private Map<String, Object> collect(Team team, int season, ChildSpec spec, PersonalizedTacticView used,
                                        List<PlayerView> squad, Set<String> playedBefore, int endDay) {
        long teamId = team.getId();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ScheduleView v : schedule(teamId, season)) {
            Map<String, Object> row = objectMapper.convertValue(v, new TypeReference<LinkedHashMap<String, Object>>() {});
            Double xgFor = null, xgAgainst = null;
            Optional<MatchStats> stats = matchStatsRepository.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                    v.getCompetitionId(), season, v.getRoundNumber(), v.getTeamId1(), v.getTeamId2());
            if (stats.isPresent() && "played".equals(v.getStatus())) {
                boolean home = v.getTeamId1() == teamId;
                MatchStats s = stats.get();
                xgFor = (home ? s.getHomeXg() : s.getAwayXg()) / 100.0;
                xgAgainst = (home ? s.getAwayXg() : s.getHomeXg()) / 100.0;
            }
            row.put("xgFor", xgFor);
            row.put("xgAgainst", xgAgainst);
            row.put("inRun", !playedBefore.contains(key(v)));
            matches.add(row);
        }

        long leagueId = team.getCompetitionId();
        List<TeamCompetitionView> leagueTable = competitionDisplayService.getTeamDetails(leagueId, teamId);
        List<Map<String, Object>> competitions = new ArrayList<>();
        for (Map<String, Object> source : competitionDisplayService.getTeamCompetitions(teamId)) {
            Map<String, Object> comp = new LinkedHashMap<>(source);
            long competitionId = ((Number) comp.get("competitionId")).longValue();
            boolean league = competitionRepository.findById(competitionId).map(Competition::isLeague).orElse(false);
            Integer position = null;
            Integer points = null;
            if (league) {
                List<TeamCompetitionView> table = competitionId == leagueId ? leagueTable
                        : competitionDisplayService.getTeamDetails(competitionId, teamId);
                for (TeamCompetitionView row : table) {
                    if (row.getTeamId() == teamId) {
                        position = row.getPosition();
                        points = parseInt(row.getPoints());
                    }
                }
            }
            comp.put("position", position);
            comp.put("points", points);
            // The cup progress model also tags leagues (e.g. "WINNER" for any finished league), so a
            // league title is decided by the final place alone.
            comp.put("trophy", league ? position != null && position == 1 : "WINNER".equals(comp.get("status")));
            competitions.add(comp);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teamId", teamId);
        result.put("teamName", team.getName());
        result.put("leagueId", leagueId);
        result.put("leagueName", competitionRepository.findById(leagueId).map(Competition::getName).orElse(null));
        result.put("season", season);
        result.put("startDay", spec.startDay());
        result.put("endDay", endDay);
        result.put("engine", spec.engine());
        result.put("tactic", used);
        result.put("squad", squad);
        result.put("matches", matches);
        result.put("competitions", competitions);
        result.put("leagueTable", leagueTable);
        return result;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
