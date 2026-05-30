package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.TeamTalkService;
import com.footballmanagergamesimulator.user.UserContext;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-match / half-time / post-match team talks and individual player talks.
 * Lifted out of {@link CompetitionController} so all team-talk REST surface
 * lives in one place. URLs keep the {@code /competition/...} prefix so the
 * Angular frontend needs no changes.
 *
 * <p>Holds the per-round {@code teamTalkUsedThisRound} latch — set by the
 * legacy and PRE_MATCH paths, cleared by {@link #resetTeamTalkUsed()} which
 * the calendar driver invokes once all match events for the day complete.
 */
@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TeamTalkController {

    private final TeamTalkService teamTalkService;
    private final RoundRepository roundRepository;
    private final UserContext userContext;

    private boolean teamTalkUsedThisRound = false;

    public TeamTalkController(TeamTalkService teamTalkService, RoundRepository roundRepository, UserContext userContext) {
        this.teamTalkService = teamTalkService;
        this.roundRepository = roundRepository;
        this.userContext = userContext;
    }

    /** Called by the calendar driver after all match events for the day
     *  complete, so the pre-match talk can be used again for the next match. */
    public void resetTeamTalkUsed() {
        teamTalkUsedThisRound = false;
        teamTalkService.resetAllForNewMatch();
    }

    @GetMapping("/teamTalkStatus")
    public Map<String, Object> getTeamTalkStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("used", teamTalkUsedThisRound);
        status.put("round", currentRound());
        return status;
    }

    /**
     * Legacy team talk endpoint (backward compatible).
     * Maps old types (calm, motivated, aggressive, no_pressure) to new PRE_MATCH phase.
     */
    @PostMapping("/teamTalk")
    public Map<String, Object> giveTeamTalk(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (teamTalkUsedThisRound) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Team talk already used this round.");
            return response;
        }

        String type = body.get("type");
        String mappedType = switch (type) {
            case "calm" -> "focus";
            case "motivated" -> "show_passion";
            case "aggressive" -> "expect_win";
            case "no_pressure" -> "no_pressure";
            default -> type;
        };

        long teamId = userContext.getTeamId(request);
        int season = currentSeason();
        Map<String, Object> result = teamTalkService.giveTeamTalk(teamId, "PRE_MATCH", mappedType, null, season, currentRound());

        if (Boolean.TRUE.equals(result.get("success"))) {
            teamTalkUsedThisRound = true;
        }
        return result;
    }

    /**
     * Expanded team talk endpoint with phase support (PRE_MATCH, HALF_TIME, POST_MATCH).
     */
    @PostMapping("/teamTalkExpanded")
    public Map<String, Object> giveExpandedTeamTalk(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String phase = body.getOrDefault("phase", "PRE_MATCH");
        String type = body.get("type");
        String matchContext = body.get("matchContext");
        long teamId = userContext.getTeamId(request);
        int season = currentSeason();

        Map<String, Object> result = teamTalkService.giveTeamTalk(teamId, phase, type, matchContext, season, currentRound());

        if ("PRE_MATCH".equals(phase) && Boolean.TRUE.equals(result.get("success"))) {
            teamTalkUsedThisRound = true;
        }
        return result;
    }

    @GetMapping("/teamTalkOptions/{phase}")
    public List<Map<String, Object>> getTeamTalkOptions(
            @PathVariable String phase,
            @RequestParam(required = false, defaultValue = "") String matchContext) {
        return teamTalkService.getAvailableTalks(phase, matchContext);
    }

    @PostMapping("/playerTalk")
    public Map<String, Object> givePlayerTalk(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long teamId = userContext.getTeamId(request);
        long playerId = ((Number) body.get("playerId")).longValue();
        String type = (String) body.get("type");
        int season = currentSeason();
        return teamTalkService.giveIndividualTalk(teamId, playerId, type, season);
    }

    @GetMapping("/playerTalkOptions")
    public List<Map<String, Object>> getPlayerTalkOptions() {
        return teamTalkService.getIndividualTalkOptions();
    }

    private int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(Long::intValue).orElse(1);
    }

    private long currentRound() {
        return roundRepository.findById(1L).map(Round::getRound).orElse(1L);
    }
}
