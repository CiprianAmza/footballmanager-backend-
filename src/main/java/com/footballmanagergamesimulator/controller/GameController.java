package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/game")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class GameController {

    @Autowired
    GameAdvanceService gameAdvanceService;

    @Autowired
    CalendarService calendarService;

    @Autowired
    GameCalendarRepository gameCalendarRepository;

    @Autowired
    PressConferenceService pressConferenceService;

    @Autowired
    YouthAcademyService youthAcademyService;

    @Autowired
    SponsorshipService sponsorshipService;

    @Autowired
    BoardRequestService boardRequestService;

    @Autowired
    FacilityUpgradeService facilityUpgradeService;

    @Autowired
    AwardService awardService;

    @PostMapping("/advance")
    public Map<String, Object> advance() {
        int season = getCurrentSeason();
        return gameAdvanceService.advance(season);
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        int season = getCurrentSeason();
        return gameAdvanceService.getGameState(season);
    }

    @PostMapping("/advanceToDay/{day}")
    public Map<String, Object> advanceToDay(@PathVariable int day) {
        int season = getCurrentSeason();
        return gameAdvanceService.advanceToDay(season, day);
    }

    @PostMapping("/unpause")
    public Map<String, Object> unpause() {
        int season = getCurrentSeason();
        GameCalendar cal = calendarService.getOrCreateCalendar(season);
        cal.setPaused(false);
        gameCalendarRepository.save(cal);
        return gameAdvanceService.advance(season);
    }

    // ==================== PRESS CONFERENCE ====================

    @PostMapping("/pressConference/{id}/respond")
    public Map<String, Object> respondToPressConference(
            @PathVariable long id,
            @RequestBody Map<String, String> body) {
        String responseType = body.get("responseType");
        Map<String, Object> pressResult = pressConferenceService.respondToPressConference(id, responseType);

        // Auto-unpause and continue advancing after press conference response
        int season = getCurrentSeason();
        GameCalendar cal = calendarService.getOrCreateCalendar(season);
        cal.setPaused(false);
        gameCalendarRepository.save(cal);

        // Include updated game state so frontend can refresh
        Map<String, Object> gameState = gameAdvanceService.getGameState(season);
        pressResult.put("gameState", gameState);
        return pressResult;
    }

    // ==================== YOUTH ACADEMY ====================

    @GetMapping("/youthAcademy/{teamId}")
    public List<YouthPlayer> getYouthAcademy(@PathVariable long teamId) {
        return youthAcademyService.getYouthSquad(teamId);
    }

    @PostMapping("/youthAcademy/promote/{youthPlayerId}")
    public Human promoteYouthPlayer(@PathVariable long youthPlayerId) {
        return youthAcademyService.promoteToFirstTeam(youthPlayerId, GameAdvanceService.HUMAN_TEAM_ID);
    }

    // ==================== SPONSORSHIPS ====================

    @GetMapping("/sponsorships/{teamId}")
    public Map<String, Object> getSponsorships(@PathVariable long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", sponsorshipService.getActiveSponsors(teamId));
        result.put("offered", sponsorshipService.getOfferedSponsors(teamId));
        return result;
    }

    @PostMapping("/sponsorship/{id}/accept")
    public Sponsorship acceptSponsorship(@PathVariable long id) {
        return sponsorshipService.acceptSponsorship(id);
    }

    @PostMapping("/sponsorship/{id}/reject")
    public Sponsorship rejectSponsorship(@PathVariable long id) {
        return sponsorshipService.rejectSponsorship(id);
    }

    // ==================== BOARD REQUESTS ====================

    @GetMapping("/boardRequests/{teamId}")
    public List<BoardRequest> getBoardRequests(@PathVariable long teamId) {
        int season = getCurrentSeason();
        return boardRequestService.getBoardRequests(teamId, season);
    }

    // ==================== FACILITIES ====================

    @GetMapping("/facilities/{teamId}")
    public Map<String, Object> getFacilities(@PathVariable long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("facilities", facilityUpgradeService.getFacilities(teamId));
        result.put("upgradesInProgress", facilityUpgradeService.getUpgradesInProgress(teamId));
        return result;
    }

    @PostMapping("/facilities/upgrade")
    public FacilityUpgrade startFacilityUpgrade(@RequestBody Map<String, Object> body) {
        long teamId = ((Number) body.get("teamId")).longValue();
        String facilityType = (String) body.get("facilityType");
        int season = getCurrentSeason();
        return facilityUpgradeService.startUpgrade(teamId, facilityType, season);
    }

    // ==================== AWARDS ====================

    @GetMapping("/awards/{season}")
    public List<Award> getAwards(@PathVariable int season) {
        return awardService.getAwardsForSeason(season);
    }

    // ==================== HELPERS ====================

    private int getCurrentSeason() {
        List<GameCalendar> calendars = gameCalendarRepository.findAll();
        if (!calendars.isEmpty()) {
            return calendars.stream()
                    .mapToInt(GameCalendar::getSeason)
                    .max()
                    .orElse(1);
        }
        return 1;
    }
}
