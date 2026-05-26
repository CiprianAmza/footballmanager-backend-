package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.FriendlyMatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/friendly")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class FriendlyController {

    @Autowired
    private FriendlyMatchService friendlyMatchService;

    /**
     * Get available opponents for a friendly match, sorted by reputation.
     */
    @GetMapping("/opponents/{teamId}")
    public List<Map<String, Object>> getAvailableOpponents(@PathVariable long teamId) {
        return friendlyMatchService.getAvailableOpponents(teamId);
    }

    /**
     * Get available days for scheduling a friendly (pre-season + winter break).
     */
    @GetMapping("/availableDays/{teamId}/{season}")
    public List<Map<String, Object>> getAvailableDays(@PathVariable long teamId, @PathVariable int season) {
        return friendlyMatchService.getAvailableDays(teamId, season);
    }

    /**
     * Schedule a friendly match.
     */
    @PostMapping("/schedule")
    public Map<String, Object> scheduleFriendly(@RequestBody Map<String, Object> request) {
        long teamId = ((Number) request.get("teamId")).longValue();
        long opponentTeamId = ((Number) request.get("opponentTeamId")).longValue();
        int day = ((Number) request.get("day")).intValue();
        int season = ((Number) request.get("season")).intValue();
        return friendlyMatchService.scheduleFriendly(teamId, opponentTeamId, day, season);
    }

    /**
     * Cancel a scheduled friendly.
     */
    @DeleteMapping("/cancel/{matchId}")
    public Map<String, Object> cancelFriendly(@PathVariable long matchId) {
        return friendlyMatchService.cancelFriendly(matchId);
    }

    /**
     * Get all friendly matches for a team in a season.
     */
    @GetMapping("/matches/{teamId}/{season}")
    public List<Map<String, Object>> getFriendlyMatches(@PathVariable long teamId, @PathVariable int season) {
        return friendlyMatchService.getFriendlyMatches(teamId, season);
    }
}
