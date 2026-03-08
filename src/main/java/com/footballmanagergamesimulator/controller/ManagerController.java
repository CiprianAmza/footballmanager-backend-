package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
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

    /**
     * Full career history for a specific manager
     */
    @GetMapping("/history/{managerId}")
    public List<ManagerHistory> getManagerHistory(@PathVariable long managerId) {
        return managerHistoryRepository.findAllByManagerId(managerId);
    }

    /**
     * Manager profile with career summary
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
        if (manager.getTeamId() != null && manager.getTeamId() > 0) {
            Team team = teamRepository.findById(manager.getTeamId()).orElse(null);
            profile.put("currentTeamId", manager.getTeamId());
            profile.put("currentTeamName", team != null ? team.getName() : "Unknown");
        } else {
            profile.put("currentTeamId", 0);
            profile.put("currentTeamName", "Unemployed");
        }

        // Summary stats
        int totalGames = history.stream().mapToInt(ManagerHistory::getGamesPlayed).sum();
        int totalWins = history.stream().mapToInt(ManagerHistory::getWins).sum();
        int totalDraws = history.stream().mapToInt(ManagerHistory::getDraws).sum();
        int totalLosses = history.stream().mapToInt(ManagerHistory::getLosses).sum();
        double winPercentage = totalGames > 0 ? (double) totalWins / totalGames * 100.0 : 0;

        long totalTrophies = history.stream()
                .filter(h -> h.getTrophiesWon() != null && !h.getTrophiesWon().isEmpty())
                .mapToLong(h -> h.getTrophiesWon().split(",").length)
                .sum();

        long teamsManaged = history.stream().map(ManagerHistory::getTeamId).distinct().count();
        int seasonsManaged = history.size();

        profile.put("totalGames", totalGames);
        profile.put("totalWins", totalWins);
        profile.put("totalDraws", totalDraws);
        profile.put("totalLosses", totalLosses);
        profile.put("winPercentage", Math.round(winPercentage * 10.0) / 10.0);
        profile.put("totalTrophies", totalTrophies);
        profile.put("teamsManaged", teamsManaged);
        profile.put("seasonsManaged", seasonsManaged);
        profile.put("history", history);

        return profile;
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

        humanRepository.save(manager);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("attendPressConferences", manager.isAttendPressConferences());
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
