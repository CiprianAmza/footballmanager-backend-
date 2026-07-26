package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.AdminController;
import com.footballmanagergamesimulator.controller.TeamTalkController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/**
 * Thin advance-loop orchestrator. Pumps the calendar one day at a time,
 * delegating per-event work to {@link CalendarEventDispatcher} and per-day
 * match batches to {@link MatchdayBatchProcessor}.
 *
 * <p>Surface preserved as before so {@link com.footballmanagergamesimulator.controller.GameController}
 * doesn't churn: {@link #advance}, {@link #getGameState}, {@link #advanceToDay}.
 *
 * <p>Job-offer pause + opportunistic offer generation also stay here because
 * they wrap the advance pump (a pending offer blocks CONTINUE).
 */
@Service
public class GameAdvanceService {

    @Autowired private UserContext userContext;
    @Autowired private CalendarService calendarService;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private TeamTalkController teamTalkController;
    @Autowired private UserRepository userRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired @Lazy private JobOfferService jobOfferService;
    @Autowired @Lazy private AdminController adminController;

    @Autowired private CalendarEventDispatcher calendarEventDispatcher;
    @Autowired private MatchdayBatchProcessor matchdayBatchProcessor;
    @Autowired private InjuryTimelineService injuryTimelineService;
    @Autowired @Lazy private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired private GameLock gameLock;

    private final Random random = new Random();

    private static final java.util.Set<Integer> MONTH_START_DAYS = Set.of(
            1, 32, 62, 93, 123, 154, 185, 213, 244, 274, 305, 335);

    /**
     * Important event types that should stop auto-advancing and show to the user.
     * Everything else (training, injury updates, analytics reports, etc.) processes silently.
     */
    private static final Set<String> IMPORTANT_EVENT_TYPES = Set.of(
            "MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN", "MATCH_FRIENDLY",
            "EUROPEAN_DRAW",
            "PRESS_CONFERENCE", "BOARD_MEETING", "AWARDS_CEREMONY",
            "SEASON_START", "SEASON_END", "SEASON_TRANSITION",
            "TRANSFER_WINDOW_OPEN", "TRANSFER_WINDOW_CLOSE",
            "SPONSOR_OFFER", "NATIONAL_TEAM_CALL", "YOUTH_ACADEMY_REPORT"
    );

    public Map<String, Object> advance(int season) {
        return advance(season, false);
    }

    /**
     * Server-owned room advancement. The room coordinator has already made the
     * continue decision; this method processes exactly one current game day.
     * The scope is intentionally explicit so future pause checks cannot fall
     * back to scanning unrelated users.
     */
    public Map<String, Object> advanceOneDayUnattended(int season, Set<Integer> roomUserIds) {
        return advanceOneDayUnattended(season, -1, roomUserIds, true);
    }

    /**
     * Room coordinator entry point. The expected date check deliberately lives
     * after acquiring GameLock and before any event, calendar, fixture, or
     * economy side effect. A recovery therefore adopts the already-advanced
     * result instead of ever running the next day.
     */
    public Map<String, Object> advanceOneDayUnattended(int expectedSeason, int expectedDay,
                                                        Set<Integer> roomUserIds, boolean forceContinue) {
        if (roomUserIds == null || roomUserIds.isEmpty()) {
            throw new IllegalArgumentException("roomUserIds must not be empty");
        }
        Set<Long> roomTeamIds = userRepository.findAllById(roomUserIds).stream()
                .map(User::getTeamId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        gameLock.lock();
        try {
            GameCalendar current = gameCalendarRepository.findTopByOrderBySeasonDesc()
                    .orElseThrow(() -> new IllegalStateException("calendar missing"));
            if (expectedDay > 0) {
                com.footballmanagergamesimulator.multiplayer.RoomDate expected =
                        new com.footballmanagergamesimulator.multiplayer.RoomDate(expectedSeason, expectedDay);
                com.footballmanagergamesimulator.multiplayer.RoomDate actual =
                        com.footballmanagergamesimulator.multiplayer.RoomDate.of(current);
                if (actual.isAfter(expected)) {
                    return roomResult("ALREADY_ADVANCED", current);
                }
                if (actual.compareTo(expected) < 0) {
                    throw new IllegalStateException("ROOM_CALENDAR_BEHIND_CYCLE_DATE");
                }
            }
            Map<String, Object> result = advanceLocked(expectedSeason, forceContinue,
                    new AdvanceScope(roomUserIds, roomTeamIds));
            GameCalendar after = gameCalendarRepository.findTopByOrderBySeasonDesc()
                    .orElseThrow(() -> new IllegalStateException("calendar missing after advance"));
            com.footballmanagergamesimulator.multiplayer.RoomDate expected =
                    new com.footballmanagergamesimulator.multiplayer.RoomDate(expectedSeason, expectedDay > 0 ? expectedDay : current.getCurrentDay());
            com.footballmanagergamesimulator.multiplayer.RoomDate actualAfter =
                    com.footballmanagergamesimulator.multiplayer.RoomDate.of(after);
            if (expectedDay > 0) {
                com.footballmanagergamesimulator.multiplayer.RoomDate before =
                        com.footballmanagergamesimulator.multiplayer.RoomDate.of(current);
                boolean unchanged = actualAfter.equalsDate(before);
                boolean advancedExactlyOnce = isExactlyNextDate(expected, actualAfter);
                if (!unchanged && !advancedExactlyOnce) {
                    throw new IllegalStateException("ROOM_ADVANCE_NOT_EXACTLY_ONE_DAY");
                }
                if (Boolean.TRUE.equals(result.get("paused")) && !unchanged) {
                    // A season-transition event may report paused for its next
                    // input while the canonical calendar has already moved to
                    // season+1/day1. That is still one successful day advance.
                    result.put("paused", false);
                }
            }
            result.put("season", after.getSeason());
            result.put("day", after.getCurrentDay());
            if (Boolean.TRUE.equals(result.get("paused"))) {
                result.put("roomAdvanceStatus", "BLOCKED");
                result.put("blockerCode", result.getOrDefault("reason", "ADVANCE_BLOCKED"));
                result.put("blockerMessage", result.getOrDefault("blockingEvent", "Room input is required"));
            } else {
                result.put("roomAdvanceStatus", "ADVANCED");
            }
            return result;
        } finally {
            gameLock.unlock();
        }
    }

    static boolean isExactlyNextDate(com.footballmanagergamesimulator.multiplayer.RoomDate expected,
                                     com.footballmanagergamesimulator.multiplayer.RoomDate actual) {
        return (actual.season() == expected.season() && actual.day() == expected.day() + 1)
                || (actual.season() == expected.season() + 1 && actual.day() == 1);
    }

    private Map<String, Object> roomResult(String status, GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomAdvanceStatus", status);
        result.put("season", calendar.getSeason());
        result.put("day", calendar.getCurrentDay());
        result.put("paused", false);
        result.put("eventsProcessed", List.of());
        return result;
    }

    /** Fast Forward validates unattended mode once when the job starts. */
    Map<String, Object> advanceFastForward(int season) {
        return advance(season, true);
    }

    private Map<String, Object> advance(int season, boolean unattendedConfirmed) {
        return advance(season, unattendedConfirmed, null);
    }

    private Map<String, Object> advance(int season, boolean unattendedConfirmed, AdvanceScope scope) {
        // gameLock so two concurrent /game/advance calls (e.g. autoContinue
        // firing rapidly, double-click on CONTINUE, two tabs) AND user squad
        // mutations (youth promotion etc.) can't update the same human/team rows
        // in parallel. H2 row-level lock timeout was the symptom — single-user
        // game, simplest correct serialization. ReentrantLock so advanceToDay's
        // re-entrant advance() calls don't self-deadlock.
        gameLock.lock();
        try {
            return advanceLocked(season, unattendedConfirmed, scope);
        } finally {
            gameLock.unlock();
        }
    }

    private Map<String, Object> advanceLocked(int season, boolean unattendedConfirmed, AdvanceScope scope) {
        // Recovery: any PROCESSING events left over from a previous crash/exception
        // are flipped back to PENDING so they can be retried. Safe because we're
        // synchronized and nothing else is mid-processing right now.
        GameCalendar calendar = scope != null
                ? gameCalendarRepository.findTopByOrderBySeasonDesc().orElseThrow(() -> new IllegalStateException("calendar missing"))
                : calendarService.getOrCreateCalendar(season);
        // The canonical row is authoritative at season boundaries. All event
        // queries below must use the season that was actually read, never the
        // caller's stale cycle parameter.
        season = calendar.getSeason();
        boolean alwaysContinue = scope != null ? unattendedConfirmed
                : unattendedConfirmed || isAlwaysContinueActive();

        // Room blockers are checked before recovery, injuries, daily
        // maintenance, or any other side effect. A BLOCKED cycle therefore
        // remains observationally stable until an explicit Continue/FF retry.
        if (scope != null && !alwaysContinue) {
            Map<String, Object> jobOfferBlock = checkJobOfferPause(calendar.getSeason(), calendar, scope);
            if (jobOfferBlock != null) return jobOfferBlock;
            Map<String, Object> liveMatchBlock = checkLiveMatchPause(calendar.getSeason(), calendar, scope);
            if (liveMatchBlock != null) return liveMatchBlock;
        }

        int released = calendarService.releaseStuckEvents(calendar.getSeason());
        if (released > 0) {
            System.out.println(">>> advance: released " + released + " stuck PROCESSING events back to PENDING");
        }

        injuryTimelineService.processRecoveries(calendar.getSeason(), calendar.getCurrentDay());
        if ("MORNING".equals(calendar.getCurrentPhase())) {
            calendarEventDispatcher.processDailyMaintenance(calendar.getSeason(), calendar.getCurrentDay());
        }

        if (scope != null && unattendedConfirmed) {
            liveMatchSimulationService.finishAndCommitForTeams(scope.roomTeamIds());
        }

        if (calendar.isManagerFired() && !alwaysContinue) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("paused", true);
            result.put("reason", "MANAGER_FIRED");
            result.put("season", season);
            result.put("day", calendar.getCurrentDay());
            result.put("phase", calendar.getCurrentPhase());
            result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
            return result;
        }

        // Hard pause: any logged-in user has a pending job offer → block advance
        // until they accept/decline. Frontend shows a banner / inbox modal.
        if (scope == null && !alwaysContinue) {
            Map<String, Object> jobOfferBlock = checkJobOfferPause(season, calendar, scope);
            if (jobOfferBlock != null) return jobOfferBlock;
        }

        // Hard pause: an uncommitted live-match session exists for a human user
        // (typically because the user refreshed the browser mid-match). Block
        // advance and signal the FE to resume the live modal — without this,
        // the calendar would silently roll past the matchday with no result
        // for the human team.
        if (scope == null && !alwaysContinue) {
            Map<String, Object> liveMatchBlock = checkLiveMatchPause(season, calendar, scope);
            if (liveMatchBlock != null) return liveMatchBlock;
        }

        // Clear pause flag - pressing CONTINUE is the user input that unpauses
        if (calendar.isPaused()) {
            calendar.setPaused(false);
            gameCalendarRepository.save(calendar);
        }

        // Run the offer generator — admin force OR sacking-driven (a club sacks
        // its AI manager and offers the open seat to an in-band human).
        if (scope == null) {
            try {
                maybeGenerateForcedJobOffers();
            } catch (Exception ex) {
                System.err.println("Job offer generator failed: " + ex);
            }
        }

        List<Map<String, Object>> processedEvents = new ArrayList<>();
        String pauseReason = null;
        String blockingEvent = null;
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
                // Atomic DB claim — if two advance() calls race, only the one whose
                // UPDATE matched PENDING gets to process this event. The other skips.
                if (!calendarService.claimEvent(event.getId())) continue;
                String type = event.getEventType();
                if ("MATCH_LEAGUE".equals(type) || "MATCH_CUP".equals(type) || "MATCH_EUROPEAN".equals(type)) {
                    matchEvents.add(event);
                } else {
                    otherEvents.add(event);
                }
            }

            // Process non-match events
            for (CalendarEvent event : otherEvents) {
                Map<String, Object> eventResult;
                try {
                    eventResult = calendarEventDispatcher.processEvent(event, calendar);
                } catch (RuntimeException ex) {
                    // Don't leave the event stuck in PROCESSING — release it so the next
                    // advance() retries instead of silently skipping it forever.
                    calendarService.releaseEvent(event.getId());
                    System.err.println(">>> processEvent failed for event id=" + event.getId()
                            + " type=" + event.getEventType() + " day=" + event.getDay()
                            + " — released back to PENDING. " + ex);
                    ex.printStackTrace();
                    throw ex;
                }
                calendarService.markEventCompleted(event.getId());

                boolean isImportant = IMPORTANT_EVENT_TYPES.contains(event.getEventType())
                        && !Boolean.FALSE.equals(eventResult.get("userRelevant"));
                if (isImportant) {
                    processedEvents.add(eventResult);
                }

                if (!alwaysContinue && eventResult.containsKey("awaitingInput")
                        && (boolean) eventResult.get("awaitingInput")) {
                    calendar.setPaused(true);
                    gameCalendarRepository.save(calendar);
                    pauseReason = "AWAITING_INPUT";
                    blockingEvent = event.getEventType();
                    break;
                }
            }

            // Batch-process all match events at once
            if (!matchEvents.isEmpty() && !calendar.isPaused()) {
                Map<String, Object> batchResult;
                try {
                    batchResult = matchdayBatchProcessor.processBatchMatches(matchEvents, calendar);
                } catch (RuntimeException ex) {
                    for (CalendarEvent me : matchEvents) {
                        calendarService.releaseEvent(me.getId());
                    }
                    System.err.println(">>> processBatchMatches failed for "
                            + matchEvents.size() + " events on day " + calendar.getCurrentDay()
                            + " — released back to PENDING. " + ex);
                    ex.printStackTrace();
                    throw ex;
                }
                processedEvents.add(batchResult);

                if (scope != null && !alwaysContinue && Boolean.TRUE.equals(batchResult.get("hasLiveMatch"))) {
                    calendar.setPaused(true);
                    gameCalendarRepository.save(calendar);
                    pauseReason = "LIVE_MATCH_PENDING";
                }

                for (CalendarEvent me : matchEvents) {
                    calendarService.markEventCompleted(me.getId());
                }

                // Reset team talk so it can be used again before next match
                teamTalkController.resetTeamTalkUsed();
            }

            // Advance to next phase (MORNING→AFTERNOON→EVENING→next day MORNING)
            if (!calendar.isPaused()) {
                calendarService.advancePhase(calendar);
                gameCalendarRepository.save(calendar);
            }
        }

        // Monthly wage processing: if we moved to a new day that's a month start
        if (calendar.getCurrentDay() != startDay && MONTH_START_DAYS.contains(calendar.getCurrentDay())) {
            calendarEventDispatcher.processMonthlyWages(season);
        }

        // Safety net: if we're past day 355 with no pending events, trigger season transition
        if (calendar.getCurrentDay() >= 355 && !calendar.isPaused()) {
            CalendarEvent pendingEvent = calendarService.getNextPendingEvent(season);
            if (pendingEvent == null) {
                // No more events this season — check if season transition is needed
                List<GameCalendar> nextSeasonCal = gameCalendarRepository.findBySeason(season + 1);
                if (nextSeasonCal.isEmpty()) {
                    System.out.println("=== SAFETY NET: No pending events and no next season. Triggering season transition. ===");
                    calendarEventDispatcher.processSeasonTransition(season);
                    // Re-read calendar state
                    List<GameCalendar> newCals = gameCalendarRepository.findBySeason(season + 1);
                    if (!newCals.isEmpty()) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("season", season);
                        result.put("day", calendar.getCurrentDay());
                        result.put("phase", calendar.getCurrentPhase());
                        result.put("dayOfWeek", calendarService.getDayOfWeek(calendar.getCurrentDay()));
                        result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
                        result.put("seasonPhase", "END_OF_SEASON");
                        result.put("eventsProcessed", List.of(Map.of(
                                "type", "SEASON_TRANSITION",
                                "title", "New Season Preparation",
                                "details", "New season " + (season + 1) + " is starting!",
                                "newSeason", season + 1
                        )));
                        result.put("paused", !alwaysContinue);
                        if (!alwaysContinue) {
                            result.put("reason", "AWAITING_INPUT");
                            result.put("blockingEvent", "SEASON_TRANSITION");
                        }
                        result.put("transferWindowOpen", false);
                        calendar.setPaused(!alwaysContinue);
                        gameCalendarRepository.save(calendar);
                        result.put("alwaysContinue", alwaysContinue);
                        return result;
                    }
                }
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
        if (calendar.isPaused() && pauseReason != null) {
            result.put("reason", pauseReason);
            result.put("blockingEvent", blockingEvent);
        }
        result.put("transferWindowOpen", calendar.isTransferWindowOpen());
        result.put("alwaysContinue", alwaysContinue);

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
        state.put("alwaysContinue", isAlwaysContinueActive());

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

    // ============================================================
    //                    JOB OFFER INTEGRATION
    // ============================================================

    /**
     * If any human user has a pending job offer, return a paused response so
     * the frontend blocks CONTINUE until they accept/decline. Returns null
     * if there's nothing blocking.
     */
    /**
     * If any human user has an uncommitted live-match session lying around
     * (browser refresh, server-side session still cached), return a paused
     * response with the live-match signal so the FE re-opens the modal where
     * it was. Without this, the next CONTINUE press would advance the calendar
     * past the matchday and silently lose the result.
     */
    private Map<String, Object> checkLiveMatchPause(int season, GameCalendar calendar, AdvanceScope scope) {
        for (var u : scope == null ? userRepository.findAll() : userRepository.findAllById(scope.roomUserIds())) {
            if (scope != null && !scope.roomUserIds().contains(u.getId())) continue;
            if (u.getTeamId() == null) continue;
            var session = liveMatchSimulationService.findAnyUncommittedSessionForTeam(u.getTeamId());
            if (session == null) continue;

            String liveKey = LiveMatchSimulationService.buildKey(
                    session.getCompetitionId(), session.getSeason(), session.getRound(),
                    session.getTeamId1(), session.getTeamId2());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("paused", true);
            result.put("reason", "LIVE_MATCH_PENDING");
            result.put("hasLiveMatch", true);
            result.put("liveMatchKey", liveKey);
            result.put("liveMatchInteractive", !session.isFinished());
            result.put("season", season);
            result.put("day", calendar.getCurrentDay());
            result.put("phase", calendar.getCurrentPhase());
            result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
            return result;
        }
        return null;
    }

    private Map<String, Object> checkJobOfferPause(int season, GameCalendar calendar, AdvanceScope scope) {
        boolean anyPending = false;
        List<Integer> usersWithOffers = new ArrayList<>();
        for (var u : scope == null ? userRepository.findAll() : userRepository.findAllById(scope.roomUserIds())) {
            if (jobOfferService.userHasPendingOffer(u.getId())) {
                anyPending = true;
                usersWithOffers.add(u.getId());
            }
        }
        if (!anyPending) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paused", true);
        result.put("reason", "JOB_OFFER_PENDING");
        result.put("awaitingInput", true);
        result.put("usersWithOffers", usersWithOffers);
        result.put("season", season);
        result.put("day", calendar.getCurrentDay());
        result.put("phase", calendar.getCurrentPhase());
        result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
        return result;
    }

    /**
     * Single-player games normally have one configured user. In a multi-user save,
     * unattended mode is active only when every configured human manager opted in,
     * so one player cannot suppress another player's pauses. Free-agent managers
     * remain configured through User.managerId and must keep the setting active;
     * otherwise a dismissal would silently turn an unattended simulation off.
     */
    public boolean isAlwaysContinueActive() {
        List<User> users = userRepository.findAll();
        List<User> managerCareers = users.stream()
                .filter(user -> user.getCareerRole() != CareerRole.CHAIRMAN)
                .toList();

        // Chairmen have no human manager and therefore no live-match, dismissal or job-offer
        // pause to acknowledge. A chairman-only career can advance unattended. Keep the empty
        // bootstrap state false so a server with no registered player is not treated as opted in.
        if (managerCareers.isEmpty()) {
            return users.stream().anyMatch(user -> user.getCareerRole() == CareerRole.CHAIRMAN);
        }
        if (managerCareers.stream().anyMatch(user -> user.getManagerId() == null)) {
            return false;
        }

        Set<Long> managerIds = managerCareers.stream()
                .map(user -> user.getManagerId())
                .collect(java.util.stream.Collectors.toSet());

        List<com.footballmanagergamesimulator.model.Human> managers = new ArrayList<>();
        humanRepository.findAllById(managerIds).forEach(managers::add);
        return managers.size() == managerIds.size()
                && managers.stream().allMatch(com.footballmanagergamesimulator.model.Human::isAlwaysContinue);
    }

    /**
     * Process the admin force flag. Normal sacking-driven offers are evaluated
     * by MatchdayBatchProcessor only after a league round changes a table.
     */
    private void maybeGenerateForcedJobOffers() {
        if (!adminController.areJobOffersEnabled()) return;

        if (adminController.consumeForceOfferFlag()) {
            for (var u : userRepository.findAll()) {
                if (jobOfferService.userHasPendingOffer(u.getId())) continue;
                try {
                    jobOfferService.generateOpportunisticOffer(u.getId());
                } catch (Exception ex) {
                    System.err.println("Failed to force offer for user " + u.getId() + ": " + ex);
                }
            }
        }
    }
}
