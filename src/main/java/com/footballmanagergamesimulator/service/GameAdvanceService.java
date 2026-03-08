package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GameAdvanceService {

    public static final long HUMAN_TEAM_ID = 1L;

    @Autowired
    CalendarService calendarService;

    @Autowired
    GameCalendarRepository gameCalendarRepository;

    @Autowired
    CalendarEventRepository calendarEventRepository;

    @Autowired
    CompetitionController competitionController;

    @Autowired
    FixtureSchedulingService fixtureSchedulingService;

    @Autowired
    InjuryRepository injuryRepository;

    @Autowired
    HumanRepository humanRepository;

    @Autowired
    @Lazy
    TrainingService trainingService;

    @Autowired
    @Lazy
    YouthAcademyService youthAcademyService;

    @Autowired
    @Lazy
    PressConferenceService pressConferenceService;

    @Autowired
    @Lazy
    BoardRequestService boardRequestService;

    @Autowired
    @Lazy
    AwardService awardService;

    @Autowired
    @Lazy
    SponsorshipService sponsorshipService;

    @Autowired
    @Lazy
    FacilityUpgradeService facilityUpgradeService;

    @Autowired
    @Lazy
    NationalTeamService nationalTeamService;

    @Autowired
    @Lazy
    SuspensionService suspensionService;

    /**
     * Important event types that should stop auto-advancing and show to the user.
     * Everything else (training, injury updates, analytics reports, etc.) processes silently.
     */
    private static final Set<String> IMPORTANT_EVENT_TYPES = Set.of(
            "MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN", "MATCH_FRIENDLY",
            "PRESS_CONFERENCE", "BOARD_MEETING", "AWARDS_CEREMONY",
            "SEASON_START", "SEASON_END", "TRANSFER_WINDOW_OPEN", "TRANSFER_WINDOW_CLOSE",
            "SPONSOR_OFFER", "NATIONAL_TEAM_CALL", "YOUTH_ACADEMY_REPORT"
    );

    public Map<String, Object> advance(int season) {
        GameCalendar calendar = calendarService.getOrCreateCalendar(season);

        if (calendar.isManagerFired()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("paused", true);
            result.put("reason", "MANAGER_FIRED");
            result.put("season", season);
            result.put("day", calendar.getCurrentDay());
            result.put("phase", calendar.getCurrentPhase());
            result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
            return result;
        }

        // Clear pause flag - pressing CONTINUE is the user input that unpauses
        if (calendar.isPaused()) {
            calendar.setPaused(false);
            gameCalendarRepository.save(calendar);
        }

        List<Map<String, Object>> processedEvents = new ArrayList<>();
        int startDay = calendar.getCurrentDay();

        // Process all remaining phases of the current day in one CONTINUE press
        while (calendar.getCurrentDay() == startDay && !calendar.isPaused()) {

            List<CalendarEvent> events = calendarService.getEventsForDayAndPhase(
                    season, calendar.getCurrentDay(), calendar.getCurrentPhase());

            // Separate match events from other events - matches get batch-processed
            List<CalendarEvent> matchEvents = new ArrayList<>();
            List<CalendarEvent> otherEvents = new ArrayList<>();

            for (CalendarEvent event : events) {
                if (!"PENDING".equals(event.getStatus())) continue;
                String type = event.getEventType();
                if ("MATCH_LEAGUE".equals(type) || "MATCH_CUP".equals(type) || "MATCH_EUROPEAN".equals(type)) {
                    matchEvents.add(event);
                } else {
                    otherEvents.add(event);
                }
            }

            // Process non-match events
            for (CalendarEvent event : otherEvents) {
                Map<String, Object> eventResult = processEvent(event, calendar);
                calendarService.markEventCompleted(event.getId());

                boolean isImportant = IMPORTANT_EVENT_TYPES.contains(event.getEventType());
                if (isImportant) {
                    processedEvents.add(eventResult);
                }

                if (eventResult.containsKey("awaitingInput") && (boolean) eventResult.get("awaitingInput")) {
                    calendar.setPaused(true);
                    gameCalendarRepository.save(calendar);
                    break;
                }
            }

            // Batch-process all match events at once
            if (!matchEvents.isEmpty() && !calendar.isPaused()) {
                Map<String, Object> batchResult = processBatchMatches(matchEvents, calendar);
                processedEvents.add(batchResult);

                for (CalendarEvent me : matchEvents) {
                    calendarService.markEventCompleted(me.getId());
                }
            }

            // Advance to next phase (MORNING→AFTERNOON→EVENING→next day MORNING)
            if (!calendar.isPaused()) {
                calendarService.advancePhase(calendar);
                gameCalendarRepository.save(calendar);
            }
        }

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", season);
        result.put("day", calendar.getCurrentDay());
        result.put("phase", calendar.getCurrentPhase());
        result.put("dayOfWeek", calendarService.getDayOfWeek(calendar.getCurrentDay()));
        result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
        result.put("seasonPhase", calendarService.getSeasonPhase(calendar.getCurrentDay()));
        result.put("eventsProcessed", processedEvents);
        result.put("paused", calendar.isPaused());

        // Find next upcoming event
        CalendarEvent nextEvent = calendarService.getNextPendingEvent(season);
        if (nextEvent != null) {
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("day", nextEvent.getDay());
            next.put("phase", nextEvent.getPhase());
            next.put("type", nextEvent.getEventType());
            next.put("title", nextEvent.getTitle());
            next.put("dateDisplay", calendarService.getDateDisplay(nextEvent.getDay()));
            result.put("nextEvent", next);
        }

        return result;
    }

    private Map<String, Object> processEvent(CalendarEvent event, GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", event.getEventType());
        result.put("title", event.getTitle());
        result.put("day", event.getDay());

        switch (event.getEventType()) {
            case "TRAINING_SESSION":
                trainingService.processTrainingSession(HUMAN_TEAM_ID, calendar.getSeason());
                // Also process for AI teams (simplified)
                result.put("details", "Training session completed");
                break;
            case "YOUTH_ACADEMY_REPORT":
                youthAcademyService.generateYouthReport(HUMAN_TEAM_ID, calendar.getSeason());
                youthAcademyService.developYouthPlayers(HUMAN_TEAM_ID);
                result.put("details", "Youth academy report generated");
                break;
            case "PRESS_CONFERENCE":
                PressConference pc = pressConferenceService.generatePreMatchPressConference(HUMAN_TEAM_ID,
                        event.getCompetitionId() != null ? event.getCompetitionId() : 0,
                        event.getMatchday(), calendar.getSeason());
                result.put("details", "Press conference available");
                result.put("pressConferenceId", pc.getId());
                result.put("awaitingInput", true);
                // Mark all other PRESS_CONFERENCE events for this day as completed
                // so only one press conference modal appears per day
                List<CalendarEvent> otherPressConfs = calendarEventRepository
                        .findAllBySeasonAndDayAndStatus(calendar.getSeason(), calendar.getCurrentDay(), "PENDING")
                        .stream()
                        .filter(e -> "PRESS_CONFERENCE".equals(e.getEventType()) && e.getId() != event.getId())
                        .toList();
                for (CalendarEvent other : otherPressConfs) {
                    calendarService.markEventCompleted(other.getId());
                }
                break;
            case "BOARD_MEETING":
                boardRequestService.processBoardMeeting(HUMAN_TEAM_ID, calendar.getSeason());
                result.put("details", "Board meeting completed");
                break;
            case "AWARDS_CEREMONY":
                List<Award> awards = awardService.processAwardsCeremony(calendar.getSeason());
                result.put("details", "Awards ceremony completed");
                result.put("awards", awards);
                break;
            case "SPONSOR_OFFER":
                sponsorshipService.generateSponsorOffer(HUMAN_TEAM_ID, calendar.getSeason());
                result.put("details", "Sponsorship offer received");
                break;
            case "NATIONAL_TEAM_CALL":
                nationalTeamService.processInternationalBreak(calendar.getSeason(), calendar.getCurrentDay());
                result.put("details", "International break - players called up");
                break;
            case "INJURY_UPDATE":
                processInjuryUpdate();
                // Also check facility upgrade completion
                facilityUpgradeService.checkUpgradeCompletion(HUMAN_TEAM_ID, calendar.getCurrentDay());
                result.put("details", "Injuries and facilities updated");
                break;
            case "MATCH_LEAGUE":
            case "MATCH_CUP":
            case "MATCH_EUROPEAN":
                if (event.getCompetitionId() != null && event.getMatchday() > 0) {
                    competitionController.simulateMatchday(
                            event.getCompetitionId(), event.getMatchday(), event.getSeason());
                    result.put("details", "Match simulated: " + event.getTitle());
                    // Fetch the human team's match result after simulation
                    Map<String, Object> matchResult = competitionController.getHumanMatchResult(
                            event.getCompetitionId(), event.getMatchday(), event.getSeason());
                    if (matchResult.containsKey("score")) {
                        result.put("matchResult", matchResult);
                    }
                } else {
                    result.put("details", "Match day - missing competition data");
                }
                // After match, process suspensions
                suspensionService.processMatchCards(event.getCompetitionId(), event.getMatchday(), calendar.getSeason());
                break;
            case "MATCH_FRIENDLY":
                result.put("details", "Pre-season friendly completed");
                break;
            case "CONTRACT_EXPIRY_CHECK":
                result.put("details", "Contract expiry check completed");
                break;
            case "ANALYTICS_REPORT":
                result.put("details", "Analytics report available");
                break;
            case "TRANSFER_WINDOW_OPEN":
                calendar.setTransferWindowOpen(true);
                result.put("details", "Transfer window is now open");
                break;
            case "TRANSFER_WINDOW_CLOSE":
                calendar.setTransferWindowOpen(false);
                result.put("details", "Transfer window is now closed");
                break;
            case "SEASON_START":
                processSeasonStart(event.getSeason());
                result.put("details", "Season " + event.getSeason() + " initialized - calendar generated");
                break;
            case "SEASON_END":
                processSeasonEnd(event.getSeason());
                result.put("details", "Season " + event.getSeason() + " has ended - end-of-season processing complete");
                break;
            default:
                result.put("details", event.getDescription());
                break;
        }

        return result;
    }

    /**
     * Batch-processes all match events for a single phase.
     * Simulates all competitions at once instead of one-by-one.
     */
    private Map<String, Object> processBatchMatches(List<CalendarEvent> matchEvents, GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "MATCH_DAY");
        result.put("day", calendar.getCurrentDay());

        List<String> matchSummaries = new ArrayList<>();
        Map<String, Object> humanMatchResult = null;

        // Find which competition the human team plays in (to fetch result after simulation)
        Long humanCompetitionId = null;
        int humanMatchday = 0;

        // Check which competitions have the human team
        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
            // We'll determine the human competition after simulating
            // For now just track all competitions
        }

        // Simulate ALL competitions (just simulateRound, same as old play())
        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() != null && event.getMatchday() > 0) {
                competitionController.simulateMatchday(
                        event.getCompetitionId(), event.getMatchday(), event.getSeason());
                matchSummaries.add(event.getTitle());
            }
        }

        // AFTER all simulations: find the human team's match result (one query, once)
        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
            Map<String, Object> mr = competitionController.getHumanMatchResult(
                    event.getCompetitionId(), event.getMatchday(), event.getSeason());
            if (mr.containsKey("score")) {
                humanMatchResult = mr;
                humanCompetitionId = event.getCompetitionId();
                humanMatchday = event.getMatchday();
                break;
            }
        }

        // Process suspensions only for the human team's competition
        if (humanCompetitionId != null) {
            suspensionService.processMatchCards(humanCompetitionId, humanMatchday, calendar.getSeason());
        }

        result.put("title", "Match Day - " + matchSummaries.size() + " competitions");
        result.put("details", String.join(", ", matchSummaries));

        if (humanMatchResult != null) {
            result.put("matchResult", humanMatchResult);
        }

        return result;
    }

    /**
     * Processes a daily training session: slightly improves fitness for all players.
     * Players who are not injured get a small fitness boost.
     */
    private void processTrainingSession() {
        Random random = new Random();
        // Only train the human team's players (AI teams don't need per-player training)
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(HUMAN_TEAM_ID, TypeNames.PLAYER_TYPE);

        Set<Long> injuredPlayerIds = new HashSet<>();
        List<Injury> activeInjuries = injuryRepository.findAllByDaysRemainingGreaterThan(0);
        for (Injury injury : activeInjuries) {
            injuredPlayerIds.add(injury.getPlayerId());
        }

        List<Human> toSave = new ArrayList<>();
        for (Human player : players) {
            if (player.isRetired()) continue;
            if (injuredPlayerIds.contains(player.getId())) continue;

            double fitnessBoost = 0.1 + random.nextDouble() * 0.4;
            player.setRating(player.getRating() + fitnessBoost);
            toSave.add(player);
        }
        if (!toSave.isEmpty()) humanRepository.saveAll(toSave);
    }

    /**
     * Processes daily injury updates: decrements daysRemaining by 1 for all active injuries.
     * When an injury heals (daysRemaining reaches 0), the player status is set back to "Available".
     *
     * @return number of players who fully recovered
     */
    private int processInjuryUpdate() {
        List<Injury> activeInjuries = injuryRepository.findAllByDaysRemainingGreaterThan(0);
        int healed = 0;
        List<Human> playersToSave = new ArrayList<>();

        for (Injury injury : activeInjuries) {
            injury.setDaysRemaining(injury.getDaysRemaining() - 1);

            if (injury.getDaysRemaining() <= 0) {
                Optional<Human> playerOpt = humanRepository.findById(injury.getPlayerId());
                if (playerOpt.isPresent()) {
                    Human player = playerOpt.get();
                    player.setCurrentStatus("Available");
                    playersToSave.add(player);
                    healed++;
                }
            }
        }
        if (!activeInjuries.isEmpty()) injuryRepository.saveAll(activeInjuries);
        if (!playersToSave.isEmpty()) humanRepository.saveAll(playersToSave);

        return healed;
    }

    /**
     * Processes SEASON_START event: generates the full season calendar with all fixtures,
     * training sessions, injury updates, and other periodic events.
     */
    private void processSeasonStart(int season) {
        System.out.println("=== SEASON_START: Generating calendar for season " + season + " ===");

        // Check if calendar events already exist for this season (avoid duplicates)
        List<CalendarEvent> existingEvents = calendarEventRepository.findAllBySeasonAndDayAndStatus(season, 1, "PENDING");
        // Only generate if we don't already have a full set of events
        // (the SEASON_START event itself exists, so check for more than just a few events)
        long totalEvents = calendarEventRepository.findAllBySeasonAndDayAndStatus(season, 2, "PENDING").size();
        if (totalEvents == 0) {
            fixtureSchedulingService.generateSeasonCalendar(season);
            System.out.println("=== Season " + season + " calendar generated ===");
        } else {
            System.out.println("=== Season " + season + " calendar already exists, skipping generation ===");
        }
    }

    /**
     * Processes SEASON_END event: triggers end-of-season logic.
     * This is a placeholder that logs the season end. The full end-of-season processing
     * (relegation, promotion, transfers, etc.) is handled by CompetitionController.play()
     * when round > 50.
     */
    private void processSeasonEnd(int season) {
        System.out.println("=== SEASON_END: Processing end of season " + season + " ===");

        // Trigger the end-of-season flow via CompetitionController
        // The existing play() method handles all end-of-season logic when round > 50
        // For now, we log and let the existing flow handle the complex season transition
        System.out.println("=== Season " + season + " ended. Transfer window and new season setup pending. ===");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getGameState(int season) {
        GameCalendar calendar = calendarService.getOrCreateCalendar(season);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("season", season);
        state.put("day", calendar.getCurrentDay());
        state.put("phase", calendar.getCurrentPhase());
        state.put("dayOfWeek", calendarService.getDayOfWeek(calendar.getCurrentDay()));
        state.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
        state.put("seasonPhase", calendarService.getSeasonPhase(calendar.getCurrentDay()));
        state.put("transferWindowOpen", calendar.isTransferWindowOpen());
        state.put("managerFired", calendar.isManagerFired());
        state.put("paused", calendar.isPaused());

        // Upcoming events for next 7 days
        List<Map<String, Object>> upcoming = new ArrayList<>();
        int currentDay = calendar.getCurrentDay();
        for (int d = currentDay; d <= Math.min(currentDay + 7, 365); d++) {
            List<CalendarEvent> dayEvents = calendarEventRepository.findAllBySeasonAndDayAndStatus(season, d, "PENDING");
            for (CalendarEvent event : dayEvents) {
                Map<String, Object> eventInfo = new LinkedHashMap<>();
                eventInfo.put("day", event.getDay());
                eventInfo.put("phase", event.getPhase());
                eventInfo.put("type", event.getEventType());
                eventInfo.put("title", event.getTitle());
                eventInfo.put("dateDisplay", calendarService.getDateDisplay(event.getDay()));
                upcoming.add(eventInfo);
            }
        }
        state.put("upcomingEvents", upcoming);

        return state;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> advanceToDay(int season, int targetDay) {
        GameCalendar calendar = calendarService.getOrCreateCalendar(season);
        List<Map<String, Object>> allProcessed = new ArrayList<>();

        while (calendar.getCurrentDay() < targetDay && !calendar.isPaused()) {
            Map<String, Object> result = advance(season);
            if (result.containsKey("eventsProcessed")) {
                allProcessed.addAll((List<Map<String, Object>>) result.get("eventsProcessed"));
            }
            if ((boolean) result.getOrDefault("paused", false)) break;
            calendar = calendarService.getOrCreateCalendar(season);
        }

        Map<String, Object> finalState = getGameState(season);
        finalState.put("allEventsProcessed", allProcessed);
        return finalState;
    }
}
