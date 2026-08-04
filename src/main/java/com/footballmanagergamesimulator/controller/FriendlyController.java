package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.FriendlyMatchService;
import com.footballmanagergamesimulator.service.FriendlyEventService;
import org.springframework.http.HttpStatus;
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
    @Autowired
    private FriendlyEventService friendlyEventService;

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
        String purpose = String.valueOf(request.getOrDefault("purpose", "BALANCED"));
        String ruleset = String.valueOf(request.getOrDefault("ruleset", "STANDARD"));
        String venueName = request.get("venueName") == null ? null : request.get("venueName").toString();
        return friendlyMatchService.scheduleFriendly(teamId, opponentTeamId, day, season, purpose, ruleset, venueName);
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

    @GetMapping("/events/{teamId}/{season}")
    public List<Map<String, Object>> getEvents(@PathVariable long teamId, @PathVariable int season) {
        return friendlyEventService.getEvents(teamId, season);
    }

    @GetMapping("/events/world/{season}")
    public List<Map<String, Object>> getWorldEvents(@PathVariable int season) {
        return friendlyEventService.getWorldEvents(season);
    }

    @GetMapping("/plannerOptions/{teamId}")
    public Map<String, Object> getPlannerOptions(@PathVariable long teamId) {
        return friendlyEventService.plannerOptions(teamId);
    }

    @PostMapping("/events")
    public Map<String, Object> createEvent(@RequestBody Map<String, Object> request) {
        return friendlyEventService.createDraft(request);
    }

    @PostMapping("/events/{eventId}/confirm")
    public Map<String, Object> confirmEvent(@PathVariable long eventId) {
        return friendlyEventService.confirm(eventId);
    }

    @DeleteMapping("/events/{eventId}")
    public Map<String, Object> cancelEvent(@PathVariable long eventId) {
        return friendlyEventService.cancel(eventId);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> friendlyPlanningError(RuntimeException exception) {
        return Map.of("success", false, "error", exception.getMessage());
    }
}
