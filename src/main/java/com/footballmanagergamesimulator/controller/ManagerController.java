package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/managers")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ManagerController {

    @Autowired
    private ManagerHistoryRepository managerHistoryRepository;

    @Autowired
    private HumanRepository humanRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamCompetitionDetailRepository teamCompetitionDetailRepository;

    @Autowired
    private CompetitionRepository competitionRepository;

    @Autowired
    private RoundRepository roundRepository;

    /**
     * Full career history for a specific manager
     */
    @GetMapping("/history/{managerId}")
    public List<ManagerHistory> getManagerHistory(@PathVariable long managerId) {
        return managerHistoryRepository.findAllByManagerId(managerId);
    }

    /**
     * Manager profile with career summary.
     * Includes both completed-season records (frozen at end-of-season into ManagerHistory)
     * AND a live snapshot of the current season's progress, aggregated from
     * TeamCompetitionDetail. The live snapshot is added as a synthetic history entry
     * (flagged with inProgress=true) so the timeline updates after every match.
     */
    @GetMapping("/profile/{managerId}")
    public Map<String, Object> getManagerProfile(@PathVariable long managerId) {
        Human manager = humanRepository.findById(managerId).orElse(null);
        if (manager == null || manager.getTypeId() != TypeNames.MANAGER_TYPE) {
            return Map.of("error", "Manager not found");
        }

        List<ManagerHistory> history = managerHistoryRepository.findAllByManagerId(managerId);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("managerId", manager.getId());
        profile.put("managerName", manager.getName());
        profile.put("reputation", manager.getManagerReputation());
        profile.put("retired", manager.isRetired());

        // Current team
        Team currentTeam = null;
        if (manager.getTeamId() != null && manager.getTeamId() > 0) {
            currentTeam = teamRepository.findById(manager.getTeamId()).orElse(null);
            profile.put("currentTeamId", manager.getTeamId());
            profile.put("currentTeamName", currentTeam != null ? currentTeam.getName() : "Unknown");
        } else {
            profile.put("currentTeamId", 0);
            profile.put("currentTeamName", "Unemployed");
        }

        // Live current-season snapshot (only if manager has a team and is not retired).
        // Produced as a Map shaped like a ManagerHistory row so the same UI template renders it.
        Map<String, Object> currentSeason = null;
        if (currentTeam != null && !manager.isRetired()) {
            currentSeason = buildCurrentSeasonSnapshot(manager, currentTeam);
        }

        // Summary stats: include the live season so the badges update mid-season.
        int totalGames = history.stream().mapToInt(ManagerHistory::getGamesPlayed).sum();
        int totalWins = history.stream().mapToInt(ManagerHistory::getWins).sum();
        int totalDraws = history.stream().mapToInt(ManagerHistory::getDraws).sum();
        int totalLosses = history.stream().mapToInt(ManagerHistory::getLosses).sum();

        if (currentSeason != null) {
            totalGames += (int) currentSeason.getOrDefault("gamesPlayed", 0);
            totalWins  += (int) currentSeason.getOrDefault("wins", 0);
            totalDraws += (int) currentSeason.getOrDefault("draws", 0);
            totalLosses += (int) currentSeason.getOrDefault("losses", 0);
        }

        double winPercentage = totalGames > 0 ? (double) totalWins / totalGames * 100.0 : 0;

        long totalTrophies = history.stream()
                .filter(h -> h.getTrophiesWon() != null && !h.getTrophiesWon().isEmpty())
                .mapToLong(h -> h.getTrophiesWon().split(",").length)
                .sum();

        // Teams managed counts past + current (avoid double-counting current if also in history)
        Set<Long> teamSet = history.stream().map(ManagerHistory::getTeamId).collect(Collectors.toSet());
        if (currentTeam != null) teamSet.add(currentTeam.getId());
        long teamsManaged = teamSet.size();

        int seasonsManaged = history.size() + (currentSeason != null ? 1 : 0);

        profile.put("totalGames", totalGames);
        profile.put("totalWins", totalWins);
        profile.put("totalDraws", totalDraws);
        profile.put("totalLosses", totalLosses);
        profile.put("winPercentage", Math.round(winPercentage * 10.0) / 10.0);
        profile.put("totalTrophies", totalTrophies);
        profile.put("teamsManaged", teamsManaged);
        profile.put("seasonsManaged", seasonsManaged);

        // Combined timeline: past records + live current season at the end.
        List<Object> timeline = new ArrayList<>(history);
        if (currentSeason != null) timeline.add(currentSeason);
        profile.put("history", timeline);
        profile.put("currentSeason", currentSeason); // also exposed separately for explicit consumers

        return profile;
    }

    /** Compute a synthetic ManagerHistory-shaped map for the season currently in progress. */
    private Map<String, Object> buildCurrentSeasonSnapshot(Human manager, Team team) {
        int currentSeason = roundRepository.findById(1L).map(r -> (int) r.getSeason()).orElse(1);

        List<TeamCompetitionDetail> teamDetails = teamCompetitionDetailRepository.findAll().stream()
                .filter(d -> d.getTeamId() == team.getId())
                .toList();

        int gamesPlayed = teamDetails.stream().mapToInt(TeamCompetitionDetail::getGames).sum();
        int wins = teamDetails.stream().mapToInt(TeamCompetitionDetail::getWins).sum();
        int draws = teamDetails.stream().mapToInt(TeamCompetitionDetail::getDraws).sum();
        int losses = teamDetails.stream().mapToInt(TeamCompetitionDetail::getLoses).sum();
        int goalsFor = teamDetails.stream().mapToInt(TeamCompetitionDetail::getGoalsFor).sum();
        int goalsAgainst = teamDetails.stream().mapToInt(TeamCompetitionDetail::getGoalsAgainst).sum();

        // League position computed from the league this team plays in (typeId 1 or 3),
        // not from the cup standings — that's the bug the old recordManagerHistory had.
        int leaguePosition = computeLeaguePosition(team);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", 0); // synthetic, not persisted
        snapshot.put("managerId", manager.getId());
        snapshot.put("managerName", manager.getName());
        snapshot.put("teamId", team.getId());
        snapshot.put("teamName", team.getName());
        snapshot.put("seasonNumber", currentSeason);
        snapshot.put("gamesPlayed", gamesPlayed);
        snapshot.put("wins", wins);
        snapshot.put("draws", draws);
        snapshot.put("losses", losses);
        snapshot.put("goalsFor", goalsFor);
        snapshot.put("goalsAgainst", goalsAgainst);
        snapshot.put("leaguePosition", leaguePosition);
        snapshot.put("trophiesWon", "");
        snapshot.put("promoted", false);
        snapshot.put("relegated", false);
        snapshot.put("inProgress", true); // flag the frontend can use to style differently
        return snapshot;
    }

    /** Position in the team's league (typeId 1 or 3). Returns 0 if not found. */
    private int computeLeaguePosition(Team team) {
        Competition league = competitionRepository.findAll().stream()
                .filter(c -> c.getId() == team.getCompetitionId() && (c.getTypeId() == 1 || c.getTypeId() == 3))
                .findFirst()
                .orElse(null);
        if (league == null) return 0;

        List<TeamCompetitionDetail> standings = teamCompetitionDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == league.getId())
                .sorted((o1, o2) -> {
                    if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                    if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                    return o2.getGoalsFor() - o1.getGoalsFor();
                })
                .toList();

        int pos = 1;
        for (TeamCompetitionDetail d : standings) {
            if (d.getTeamId() == team.getId()) return pos;
            pos++;
        }
        return 0;
    }

    /**
     * Hall of fame leaderboard: all managers ranked by composite score
     * Score = (wins * 3 + draws) / gamesPlayed * 100 + trophiesCount * 20
     */
    @GetMapping("/leaderboard")
    public List<Map<String, Object>> getManagerLeaderboard() {
        List<Human> allManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<ManagerHistory> allHistory = managerHistoryRepository.findAll();

        // Group history by managerId
        Map<Long, List<ManagerHistory>> historyByManager = allHistory.stream()
                .collect(Collectors.groupingBy(ManagerHistory::getManagerId));

        List<Map<String, Object>> leaderboard = new ArrayList<>();

        for (Human manager : allManagers) {
            List<ManagerHistory> mgrHistory = historyByManager.getOrDefault(manager.getId(), Collections.emptyList());
            if (mgrHistory.isEmpty()) continue;

            int totalGames = mgrHistory.stream().mapToInt(ManagerHistory::getGamesPlayed).sum();
            int totalWins = mgrHistory.stream().mapToInt(ManagerHistory::getWins).sum();
            int totalDraws = mgrHistory.stream().mapToInt(ManagerHistory::getDraws).sum();
            int totalLosses = mgrHistory.stream().mapToInt(ManagerHistory::getLosses).sum();
            double winPercentage = totalGames > 0 ? (double) totalWins / totalGames * 100.0 : 0;

            long totalTrophies = mgrHistory.stream()
                    .filter(h -> h.getTrophiesWon() != null && !h.getTrophiesWon().isEmpty())
                    .mapToLong(h -> h.getTrophiesWon().split(",").length)
                    .sum();

            long teamsManaged = mgrHistory.stream().map(ManagerHistory::getTeamId).distinct().count();
            int seasonsManaged = mgrHistory.size();

            // Composite score
            double compositeScore = totalGames > 0
                    ? ((double)(totalWins * 3 + totalDraws) / totalGames) * 100.0 + totalTrophies * 20.0
                    : 0;

            // Current team
            String currentTeamName = "Unemployed";
            if (manager.getTeamId() != null && manager.getTeamId() > 0) {
                Team team = teamRepository.findById(manager.getTeamId()).orElse(null);
                if (team != null) currentTeamName = team.getName();
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("managerId", manager.getId());
            entry.put("managerName", manager.getName());
            entry.put("currentTeamName", currentTeamName);
            entry.put("totalGames", totalGames);
            entry.put("totalWins", totalWins);
            entry.put("totalDraws", totalDraws);
            entry.put("totalLosses", totalLosses);
            entry.put("winPercentage", Math.round(winPercentage * 10.0) / 10.0);
            entry.put("totalTrophies", totalTrophies);
            entry.put("teamsManaged", teamsManaged);
            entry.put("seasonsManaged", seasonsManaged);
            entry.put("reputation", manager.getManagerReputation());
            entry.put("compositeScore", Math.round(compositeScore * 10.0) / 10.0);
            entry.put("retired", manager.isRetired());

            leaderboard.add(entry);
        }

        // Sort by composite score descending
        leaderboard.sort((a, b) -> Double.compare(
                (double) b.get("compositeScore"),
                (double) a.get("compositeScore")
        ));

        return leaderboard;
    }

    /** Valid match-highlights levels. Anything else is treated as GOALS_ONLY. */
    private static final Set<String> VALID_HIGHLIGHTS_LEVELS = Set.of("NONE", "GOALS_ONLY", "KEY_MOMENTS");

    /**
     * Compute the effective matchHighlightsLevel for a manager, falling back to
     * the legacy boolean for rows persisted before the field existed.
     * Old saves: watchGoalHighlights=true → GOALS_ONLY, false → NONE.
     */
    private String effectiveHighlightsLevel(Human manager) {
        String stored = manager.getMatchHighlightsLevel();
        if (stored != null && VALID_HIGHLIGHTS_LEVELS.contains(stored)) return stored;
        return manager.isWatchGoalHighlights() ? "GOALS_ONLY" : "NONE";
    }

    /**
     * Get manager responsibilities/preferences for human team manager
     */
    @GetMapping("/responsibilities/{teamId}")
    public Map<String, Object> getResponsibilities(@PathVariable long teamId) {
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        if (managers.isEmpty()) {
            return Map.of("error", "No manager found");
        }
        Human manager = managers.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attendPressConferences", manager.isAttendPressConferences());
        result.put("viewFullMatch", manager.isViewFullMatch());
        result.put("watchGoalHighlights", manager.isWatchGoalHighlights());
        result.put("matchHighlightsLevel", effectiveHighlightsLevel(manager));
        return result;
    }

    /**
     * Update manager responsibilities/preferences
     */
    @PostMapping("/responsibilities/{teamId}")
    public Map<String, Object> updateResponsibilities(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        if (managers.isEmpty()) {
            return Map.of("error", "No manager found");
        }
        Human manager = managers.get(0);

        if (body.containsKey("attendPressConferences")) {
            manager.setAttendPressConferences((Boolean) body.get("attendPressConferences"));
        }
        if (body.containsKey("viewFullMatch")) {
            manager.setViewFullMatch((Boolean) body.get("viewFullMatch"));
        }
        if (body.containsKey("watchGoalHighlights")) {
            boolean watch = (Boolean) body.get("watchGoalHighlights");
            manager.setWatchGoalHighlights(watch);
            // Keep the new field in sync so legacy clients still toggle the right thing.
            manager.setMatchHighlightsLevel(watch ? "GOALS_ONLY" : "NONE");
        }
        if (body.containsKey("matchHighlightsLevel")) {
            String level = String.valueOf(body.get("matchHighlightsLevel"));
            if (VALID_HIGHLIGHTS_LEVELS.contains(level)) {
                manager.setMatchHighlightsLevel(level);
                // Mirror to legacy boolean: anything other than NONE counts as "watching".
                manager.setWatchGoalHighlights(!"NONE".equals(level));
            }
        }

        humanRepository.save(manager);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("attendPressConferences", manager.isAttendPressConferences());
        result.put("viewFullMatch", manager.isViewFullMatch());
        result.put("watchGoalHighlights", manager.isWatchGoalHighlights());
        result.put("matchHighlightsLevel", effectiveHighlightsLevel(manager));
        return result;
    }

    /**
     * All managers who have managed a specific team
     */
    @GetMapping("/teamHistory/{teamId}")
    public List<Map<String, Object>> getTeamManagerHistory(@PathVariable long teamId) {
        List<ManagerHistory> teamHistory = managerHistoryRepository.findAllByTeamId(teamId);

        // Group by managerId
        Map<Long, List<ManagerHistory>> byManager = teamHistory.stream()
                .collect(Collectors.groupingBy(ManagerHistory::getManagerId));

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<Long, List<ManagerHistory>> entry : byManager.entrySet()) {
            List<ManagerHistory> records = entry.getValue();
            records.sort(Comparator.comparingInt(ManagerHistory::getSeasonNumber));

            Human manager = humanRepository.findById(entry.getKey()).orElse(null);

            Map<String, Object> managerEntry = new LinkedHashMap<>();
            managerEntry.put("managerId", entry.getKey());
            managerEntry.put("managerName", records.get(0).getManagerName());
            managerEntry.put("seasonsFrom", records.get(0).getSeasonNumber());
            managerEntry.put("seasonsTo", records.get(records.size() - 1).getSeasonNumber());
            managerEntry.put("totalGames", records.stream().mapToInt(ManagerHistory::getGamesPlayed).sum());
            managerEntry.put("totalWins", records.stream().mapToInt(ManagerHistory::getWins).sum());
            managerEntry.put("reputation", manager != null ? manager.getManagerReputation() : 0);
            managerEntry.put("records", records);

            result.add(managerEntry);
        }

        return result;
    }
}
