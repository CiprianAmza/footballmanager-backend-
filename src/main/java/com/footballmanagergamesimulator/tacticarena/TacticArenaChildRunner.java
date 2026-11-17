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

    /**
     * {@code seedSalt}: mixed into every per-match seed of this process
     * ({@link com.footballmanagergamesimulator.matchplan.MatchPlanService#setSeedSalt}); 0 = the game's own seeds.
     */
    public record ChildSpec(long teamId, PersonalizedTacticView tactic, boolean autoXi,
                            int season, int startDay, int endDay, String engine, long seedSalt) {}

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
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired private MatchService matchService;
    @Autowired private CompetitionDisplayService competitionDisplayService;
    @Autowired private TacticController tacticController;
    @Autowired private GameAdvanceService gameAdvanceService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        TacticArenaSupport.startParentWatchdog(parentPid);
        Thread worker = new Thread(this::runAndExit, "tactic-arena-child");
        worker.setDaemon(false);
        worker.start();
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
        // Every copy of the base world has the same fixtures, so the same per-match seeds: a run's
        // own salt makes its matches (classic score, 2D engine, line-ups) a different draw.
        com.footballmanagergamesimulator.matchplan.MatchPlanService.setSeedSalt(spec.seedSalt());

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
            user.setEmail("tactic-arena@localhost");
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
        long leagueId = team.getCompetitionId();
        Map<Long, String> kinds = new HashMap<>();
        Long diamondId = null;
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ScheduleView v : schedule(teamId, season)) {
            Map<String, Object> row = objectMapper.convertValue(v, new TypeReference<LinkedHashMap<String, Object>>() {});
            // The schedule's competitionType says "League" for the Diamond League too: this tells them apart.
            String kind = kinds.computeIfAbsent(v.getCompetitionId(), id -> competitionKind(id, leagueId));
            row.put("competitionKind", kind);
            if ("DIAMOND".equals(kind)) diamondId = v.getCompetitionId();
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

        List<TeamCompetitionView> leagueTable = competitionDisplayService.getTeamDetails(leagueId, teamId);
        List<TeamCompetitionView> diamondTable = diamondId == null ? null
                : competitionDisplayService.getTeamDetails(diamondId, teamId);
        List<Map<String, Object>> sources = new ArrayList<>(competitionDisplayService.getTeamCompetitions(teamId));
        final Long diamondCompetition = diamondId;
        if (diamondId != null && sources.stream().noneMatch(c -> ((Number) c.get("competitionId")).longValue() == diamondCompetition)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("competitionId", diamondId);
            entry.put("name", competitionRepository.findById(diamondId).map(Competition::getName).orElse("Diamond League"));
            entry.put("typeId", Competition.DIAMOND_LEAGUE);
            sources.add(entry);
        }
        List<Map<String, Object>> competitions = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Map<String, Object> comp = new LinkedHashMap<>(source);
            long competitionId = ((Number) comp.get("competitionId")).longValue();
            Competition competition = competitionRepository.findById(competitionId).orElse(null);
            boolean diamond = competition != null && competition.isDiamondLeague();
            boolean league = competition != null && (competition.isLeague() || diamond);
            comp.put("kind", competitionId == leagueId ? "LEAGUE" : diamond ? "DIAMOND" : league ? "LEAGUE" : "CUP");
            comp.put("diamond", diamond);
            Integer position = null;
            Integer points = null;
            if (league) {
                List<TeamCompetitionView> table = competitionId == leagueId ? leagueTable
                        : diamond ? diamondTable : competitionDisplayService.getTeamDetails(competitionId, teamId);
                for (TeamCompetitionView row : table) {
                    if (row.getTeamId() == teamId) {
                        position = row.getPosition();
                        points = parseInt(row.getPoints());
                        if (diamond) {
                            // the club-competitions page gives these only for domestic leagues
                            comp.put("games", parseInt(row.getGames()));
                            comp.put("wins", parseInt(row.getWins()));
                            comp.put("draws", parseInt(row.getDraws()));
                            comp.put("loses", parseInt(row.getLoses()));
                            comp.put("goalsFor", parseInt(row.getGoalsFor()));
                            comp.put("goalsAgainst", parseInt(row.getGoalsAgainst()));
                        }
                    }
                }
                if (diamond) comp.put("of", table.size());
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
        result.put("color1", team.getColor1());
        result.put("color2", team.getColor2());
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
        Map<Long, String> kindById = new HashMap<>(kinds);
        List<Map<String, Object>> players = playerStats(teamId, season, leagueId, kindById);
        result.put("players", players);
        result.put("playerSummary", playerSummary(teamId, season, players));
        result.put("diamondLeagueId", diamondId);
        result.put("diamondTable", diamondTable);
        return result;
    }

    // ---------------------------------------------------------------- player statistics

    /** Per-player season figures, from the game's own per-match rows (Scorer), cards (MatchEvent) and minutes (PlayerSeasonStat). */
    private static final class Line {
        int apps, starts, goals, assists, rated, yellow, red, cleanSheets;
        double ratingSum;

        void add(Scorer row) {
            apps++;
            if (!row.isSubstitute()) starts++;
            goals += row.getGoals();
            assists += row.getAssists();
            if (row.getRating() > 0 && row.getRating() <= 10) {
                rated++;
                ratingSum += row.getRating();
            }
        }

        Double avg() {
            return rated == 0 ? null : Math.round(ratingSum / rated * 100) / 100.0;
        }
    }

    private List<Map<String, Object>> playerStats(long teamId, int season, long leagueId, Map<Long, String> kindById) {
        List<Scorer> rows = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, season);
        Map<Long, Line> total = new HashMap<>();
        Map<Long, Map<String, Line>> byKind = new HashMap<>();
        for (Scorer row : rows) {
            total.computeIfAbsent(row.getPlayerId(), k -> new Line()).add(row);
            String kind = kindById.computeIfAbsent(row.getCompetitionId(), id -> competitionKind(id, leagueId));
            byKind.computeIfAbsent(row.getPlayerId(), k -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new Line()).add(row);
            if ("GK".equalsIgnoreCase(row.getPosition()) && !row.isSubstitute() && row.getOpponentScore() == 0) {
                total.get(row.getPlayerId()).cleanSheets++;
            }
        }
        // Cards: the match events of the club's competitions this season.
        for (Long competitionId : kindById.keySet()) {
            for (MatchEvent event : matchEventRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
                if (event.getTeamId() != teamId) continue;
                Line line = total.computeIfAbsent(event.getPlayerId(), k -> new Line());
                if ("yellow_card".equals(event.getEventType())) line.yellow++;
                else if ("red_card".equals(event.getEventType())) line.red++;
            }
        }
        Map<Long, Integer> minutes = new HashMap<>();
        for (PlayerSeasonStat stat : playerSeasonStatRepository.findAllByTeamIdAndSeasonNumber(teamId, season)) {
            minutes.merge(stat.getPlayerId(), stat.getMinutes(), Integer::sum);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Human player : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)) {
            if (player.isRetired()) continue;
            Line t = total.getOrDefault(player.getId(), new Line());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", player.getId());
            row.put("name", player.getName());
            row.put("position", player.getPosition());
            row.put("age", player.getAge());
            row.put("rating", Math.round(player.getRating() * 10) / 10.0);
            row.put("apps", t.apps);
            row.put("starts", t.starts);
            row.put("minutes", minutes.get(player.getId()));
            row.put("goals", t.goals);
            row.put("assists", t.assists);
            row.put("avgRating", t.avg());
            row.put("yellow", t.yellow);
            row.put("red", t.red);
            row.put("cleanSheets", "GK".equalsIgnoreCase(player.getPosition()) ? t.cleanSheets : null);
            Map<String, Object> kinds = new LinkedHashMap<>();
            byKind.getOrDefault(player.getId(), Map.of()).forEach((kind, line) -> {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("apps", line.apps);
                k.put("goals", line.goals);
                k.put("assists", line.assists);
                k.put("avgRating", line.avg());
                kinds.put(kind, k);
            });
            row.put("byKind", kinds);
            out.add(row);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("apps")).reversed()
                .thenComparing((Map<String, Object> r) -> r.get("avgRating") == null ? 0.0 : (Double) r.get("avgRating"),
                        Comparator.reverseOrder()));
        return out;
    }

    private Map<String, Object> playerSummary(long teamId, int season, List<Map<String, Object>> players) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topScorer", best(players, "goals"));
        summary.put("topAssists", best(players, "assists"));
        int maxApps = players.stream().mapToInt(r -> (Integer) r.get("apps")).max().orElse(0);
        int minApps = Math.max(5, (int) Math.round(maxApps * 0.3));
        Map<String, Object> bestRated = players.stream()
                .filter(r -> r.get("avgRating") != null && (Integer) r.get("apps") >= minApps)
                .max(Comparator.comparingDouble(r -> (Double) r.get("avgRating")))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("playerId", r.get("playerId"));
                    m.put("name", r.get("name"));
                    m.put("avgRating", r.get("avgRating"));
                    m.put("apps", r.get("apps"));
                    return m;
                }).orElse(null);
        summary.put("bestRated", bestRated);
        summary.put("bestRatedMinApps", minApps);
        double sum = 0;
        int n = 0;
        for (Scorer row : scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, season)) {
            if (row.getRating() > 0 && row.getRating() <= 10) {
                sum += row.getRating();
                n++;
            }
        }
        summary.put("teamAvgRating", n == 0 ? null : Math.round(sum / n * 100) / 100.0);
        return summary;
    }

    private static Map<String, Object> best(List<Map<String, Object>> players, String field) {
        return players.stream()
                .filter(r -> (Integer) r.get(field) > 0)
                .max(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get(field))
                        .thenComparing(r -> -(Integer) r.get("apps")))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("playerId", r.get("playerId"));
                    m.put("name", r.get("name"));
                    m.put(field, r.get(field));
                    return m;
                }).orElse(null);
    }

    /** LEAGUE (the club's own league), DIAMOND, CUP, CONTINENTAL or OTHER. */
    private String competitionKind(long competitionId, long leagueId) {
        if (competitionId == leagueId) return "LEAGUE";
        Competition c = competitionRepository.findById(competitionId).orElse(null);
        if (c == null) return "OTHER";
        if (c.isDiamondLeague()) return "DIAMOND";
        if (c.isLeague()) return "LEAGUE";
        if (c.getTypeId() == Competition.LEAGUE_OF_CHAMPIONS || c.getTypeId() == Competition.STARS_CUP) return "CONTINENTAL";
        return "CUP";
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
