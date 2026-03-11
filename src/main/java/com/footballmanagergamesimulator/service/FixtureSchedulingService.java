package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FixtureSchedulingService {

    @Autowired
    private CompetitionRepository competitionRepository;

    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;

    @Autowired
    private LeagueConfigService leagueConfigService;

    /**
     * Generates all CalendarEvent entries for a full 365-day season.
     * Called once at the start of each season.
     *
     * Season calendar (365 days, starting August 1):
     * - Days 1-30: PRE_SEASON
     * - Days 31-90: EARLY_SEASON
     * - Days 91-200: MID_SEASON
     * - Days 201-210: WINTER_BREAK
     * - Days 211-320: LATE_SEASON
     * - Days 321-340: END_OF_SEASON
     * - Days 341-365: OFF_SEASON
     */
    public void generateSeasonCalendar(int season) {
        List<CalendarEvent> allEvents = new ArrayList<>();
        // Global set only used for friendlies vs all matches
        Set<Integer> allMatchDays = new HashSet<>();

        // 1. Generate match-day CalendarEvents for each competition
        List<Competition> competitions = competitionRepository.findAll();

        for (Competition comp : competitions) {
            int[] matchDays;
            String eventType;

            switch ((int) comp.getTypeId()) {
                case 1: // League (first division)
                case 3: // Second league
                    int numMatchdays = getActualRoundCount(comp.getId(), season);
                    if (numMatchdays == 0) {
                        // Fallback: estimate from team count using league-config.json
                        int numTeams = getTeamCountForCompetition(comp.getId(), season);
                        int encounters = leagueConfigService.getEncounters(comp.getName(), numTeams);
                        numMatchdays = Math.max(2, encounters * (numTeams - 1));
                    }
                    matchDays = generateLeagueMatchDays(numMatchdays);
                    eventType = "MATCH_LEAGUE";
                    break;
                case 2: // Cup
                    matchDays = new int[]{49, 91, 154, 231, 280, 315};
                    eventType = "MATCH_CUP";
                    break;
                case 4: // LoC (Champions League equivalent)
                    // Round 0: preliminary, Round 1: qualifying, Rounds 2-7: group stage,
                    // Rounds 8-10: QF, SF, Final
                    matchDays = new int[]{28, 38, 56, 77, 105, 140, 168, 196, 245, 280, 330};
                    eventType = "MATCH_EUROPEAN";
                    break;
                case 5: // Stars Cup (with group stage)
                    // Rounds 1-6: group stage, Round 7: playoff, Rounds 8-10: QF, SF, Final
                    matchDays = new int[]{42, 60, 84, 112, 147, 175, 220, 252, 295, 325};
                    eventType = "MATCH_EUROPEAN";
                    break;
                default:
                    continue;
            }

            // Resolve collisions WITHIN this competition only:
            // No two matchdays of the same competition should be on the same day,
            // and there should be at least 2 days between matchdays of the same competition.
            Set<Integer> competitionUsedDays = new HashSet<>();
            for (int i = 0; i < matchDays.length; i++) {
                matchDays[i] = resolveWithinCompetitionCollision(matchDays[i], competitionUsedDays);
                competitionUsedDays.add(matchDays[i]);
            }

            for (int i = 0; i < matchDays.length; i++) {
                // Match event
                CalendarEvent event = new CalendarEvent();
                event.setSeason(season);
                event.setDay(matchDays[i]);
                event.setPhase("EVENING");
                event.setEventType(eventType);
                event.setCompetitionId(comp.getId());
                event.setMatchday(i + 1);
                event.setStatus("PENDING");
                event.setTitle(comp.getName() + " - Matchday " + (i + 1));
                event.setPriority(1);
                allEvents.add(event);

                allMatchDays.add(matchDays[i]);

                // Press conference the day before
                CalendarEvent pressConf = new CalendarEvent();
                pressConf.setSeason(season);
                pressConf.setDay(Math.max(1, matchDays[i] - 1));
                pressConf.setPhase("AFTERNOON");
                pressConf.setEventType("PRESS_CONFERENCE");
                pressConf.setCompetitionId(comp.getId());
                pressConf.setMatchday(i + 1);
                pressConf.setStatus("PENDING");
                pressConf.setTitle("Pre-match Press Conference: " + comp.getName());
                pressConf.setPriority(1);
                allEvents.add(pressConf);
            }

            // Update CompetitionTeamInfoMatch with day values
            updateMatchDays(comp.getId(), season, matchDays);
        }

        // 2. Generate pre-season friendlies (avoid all match days)
        int[] friendlyDays = {7, 14, 21, 28};
        for (int i = 0; i < friendlyDays.length; i++) {
            int day = friendlyDays[i];
            // Just shift if there's already a match on that day
            while (allMatchDays.contains(day) && day <= 30) day++;
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("EVENING");
            event.setEventType("MATCH_FRIENDLY");
            event.setStatus("PENDING");
            event.setTitle("Pre-season Friendly");
            event.setPriority(1);
            allEvents.add(event);

            allMatchDays.add(day);
        }

        // 3. Generate daily events (training and injury updates)
        Set<Integer> restDays = new HashSet<>();
        for (int matchDay : allMatchDays) {
            restDays.add(matchDay + 1);
        }

        for (int day = 1; day <= 365; day++) {
            // Training session (morning, skip match days and rest days)
            if (!allMatchDays.contains(day) && !restDays.contains(day)) {
                CalendarEvent training = new CalendarEvent();
                training.setSeason(season);
                training.setDay(day);
                training.setPhase("MORNING");
                training.setEventType("TRAINING_SESSION");
                training.setStatus("PENDING");
                training.setTitle("Training Session");
                training.setPriority(10);
                allEvents.add(training);
            }

            // Injury update (morning, every day)
            CalendarEvent injury = new CalendarEvent();
            injury.setSeason(season);
            injury.setDay(day);
            injury.setPhase("MORNING");
            injury.setEventType("INJURY_UPDATE");
            injury.setStatus("PENDING");
            injury.setTitle("Injury & Fitness Update");
            injury.setPriority(1);
            allEvents.add(injury);
        }

        // 4. Generate periodic events
        allEvents.addAll(generatePeriodicEvents(season));

        // 5. Generate transfer window events
        allEvents.addAll(generateTransferWindowEvents(season));

        // 6. Generate season start/end events
        allEvents.addAll(generateSeasonBoundaryEvents(season));

        // Save all events in bulk
        calendarEventRepository.saveAll(allEvents);
    }

    /**
     * Counts the actual number of distinct rounds that exist in CompetitionTeamInfoMatch
     * for a given competition and season. This is the most reliable way to know
     * how many matchdays the league actually has.
     */
    private int getActualRoundCount(long competitionId, int season) {
        List<Long> rounds = competitionTeamInfoMatchRepository
                .findDistinctRoundsByCompetitionIdAndSeasonNumber(competitionId, String.valueOf(season));
        return rounds.size();
    }

    /**
     * Gets the number of teams in a competition for a given season.
     */
    private int getTeamCountForCompetition(long competitionId, int season) {
        return (int) competitionTeamInfoRepository
                .findAllBySeasonNumber(season).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .map(cti -> cti.getTeamId())
                .distinct()
                .count();
    }

    /**
     * Resolves collisions within a single competition.
     * Ensures at least 2 days between matchdays of the same competition,
     * so teams always have a rest day between matches.
     * Stays within bounds [2, 340].
     */
    private int resolveWithinCompetitionCollision(int day, Set<Integer> competitionUsedDays) {
        if (isDayFreeForCompetition(day, competitionUsedDays)) {
            return day;
        }
        // Try shifting: +1, -1, +2, -2, etc. with wide range
        for (int offset = 1; offset <= 50; offset++) {
            int forward = day + offset;
            if (forward <= 340 && isDayFreeForCompetition(forward, competitionUsedDays)) {
                return forward;
            }
            int backward = day - offset;
            if (backward >= 2 && isDayFreeForCompetition(backward, competitionUsedDays)) {
                return backward;
            }
        }
        // Fallback: just avoid same day
        for (int offset = 1; offset <= 100; offset++) {
            int forward = day + offset;
            if (forward <= 340 && !competitionUsedDays.contains(forward)) return forward;
            int backward = day - offset;
            if (backward >= 2 && !competitionUsedDays.contains(backward)) return backward;
        }
        return day; // absolute last resort
    }

    /**
     * Checks if a day is free with at least 2-day gap from other matchdays
     * in the SAME competition (so a team has a rest day between matches).
     */
    private boolean isDayFreeForCompetition(int day, Set<Integer> competitionUsedDays) {
        if (day < 2 || day > 340) return false;
        return !competitionUsedDays.contains(day)
                && !competitionUsedDays.contains(day - 1)
                && !competitionUsedDays.contains(day + 1);
    }

    /**
     * Generates league matchdays spread evenly from day 35 to day 315.
     */
    private int[] generateLeagueMatchDays(int numMatchdays) {
        int[] days = new int[numMatchdays];
        int startDay = 35;
        int endDay = 315;
        double interval = (double) (endDay - startDay) / Math.max(1, numMatchdays - 1);

        for (int i = 0; i < numMatchdays; i++) {
            int rawDay = startDay + (int) Math.round(i * interval);
            days[i] = Math.min(rawDay, endDay);
        }

        // Ensure each matchday is unique and at least 3 days from the previous
        for (int i = 1; i < days.length; i++) {
            if (days[i] <= days[i - 1] + 2) {
                days[i] = days[i - 1] + 3;
            }
        }

        return days;
    }

    /**
     * Assigns the calendar day to newly drawn cup/european round matches.
     * Called after a knockout round draw creates CompetitionTeamInfoMatch records.
     * Looks up the pre-scheduled calendar day from CalendarEvent.
     */
    public void assignMatchDayForNewRound(long competitionId, int matchday, int season) {
        // Look up the calendar day from the CalendarEvent that was pre-generated
        List<CalendarEvent> events = calendarEventRepository.findBySeasonAndCompetitionIdAndMatchday(
                season, competitionId, matchday);

        int calendarDay = 0;
        for (CalendarEvent event : events) {
            String type = event.getEventType();
            if ("MATCH_LEAGUE".equals(type) || "MATCH_CUP".equals(type) || "MATCH_EUROPEAN".equals(type)) {
                calendarDay = event.getDay();
                break;
            }
        }

        if (calendarDay > 0) {
            assignMatchDay(competitionId, season, matchday, calendarDay);
        }
    }

    /**
     * Generates periodic non-match events: board meetings, youth academy reports,
     * analytics reports, sponsor offers, and national team call-ups.
     */
    private List<CalendarEvent> generatePeriodicEvents(int season) {
        List<CalendarEvent> events = new ArrayList<>();

        // Board meetings - days 60, 120, 180, 240, 300 (AFTERNOON)
        int[] boardMeetingDays = {60, 120, 180, 240, 300};
        for (int day : boardMeetingDays) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("AFTERNOON");
            event.setEventType("BOARD_MEETING");
            event.setStatus("PENDING");
            event.setTitle("Board Meeting");
            event.setPriority(2);
            events.add(event);
        }

        // Youth academy reports - days 30, 90, 180, 270, 340 (MORNING)
        int[] youthReportDays = {30, 90, 180, 270, 340};
        for (int day : youthReportDays) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("MORNING");
            event.setEventType("YOUTH_ACADEMY_REPORT");
            event.setStatus("PENDING");
            event.setTitle("Youth Academy Report");
            event.setPriority(3);
            events.add(event);
        }

        // Analytics reports - every 14 days (MORNING)
        for (int day = 14; day <= 365; day += 14) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("MORNING");
            event.setEventType("ANALYTICS_REPORT");
            event.setStatus("PENDING");
            event.setTitle("Analytics Report");
            event.setPriority(5);
            events.add(event);
        }

        // Sponsor offers - days 20, 150, 340 (MORNING)
        int[] sponsorDays = {20, 150, 340};
        for (int day : sponsorDays) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("MORNING");
            event.setEventType("SPONSOR_OFFER");
            event.setStatus("PENDING");
            event.setTitle("Sponsor Offer");
            event.setPriority(4);
            events.add(event);
        }

        // National team call-ups - days 80, 170, 260 (MORNING), international breaks last 5 days
        int[] nationalTeamDays = {80, 170, 260};
        for (int day : nationalTeamDays) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("MORNING");
            event.setEventType("NATIONAL_TEAM_CALL");
            event.setStatus("PENDING");
            event.setTitle("International Break - National Team Call-up");
            event.setDescription("International break lasts 5 days (day " + day + " to day " + (day + 4) + ")");
            event.setPriority(2);
            events.add(event);
        }

        return events;
    }

    /**
     * Generates transfer window open/close events.
     */
    private List<CalendarEvent> generateTransferWindowEvents(int season) {
        List<CalendarEvent> events = new ArrayList<>();

        CalendarEvent summerOpen = new CalendarEvent();
        summerOpen.setSeason(season);
        summerOpen.setDay(341);
        summerOpen.setPhase("MORNING");
        summerOpen.setEventType("TRANSFER_WINDOW_OPEN");
        summerOpen.setStatus("PENDING");
        summerOpen.setTitle("Transfer Window Opens");
        summerOpen.setPriority(1);
        events.add(summerOpen);

        CalendarEvent summerClose = new CalendarEvent();
        summerClose.setSeason(season);
        summerClose.setDay(355);
        summerClose.setPhase("MORNING");
        summerClose.setEventType("TRANSFER_WINDOW_CLOSE");
        summerClose.setStatus("PENDING");
        summerClose.setTitle("Transfer Window Closes");
        summerClose.setPriority(1);
        events.add(summerClose);

        CalendarEvent winterOpen = new CalendarEvent();
        winterOpen.setSeason(season);
        winterOpen.setDay(201);
        winterOpen.setPhase("MORNING");
        winterOpen.setEventType("TRANSFER_WINDOW_OPEN");
        winterOpen.setStatus("PENDING");
        winterOpen.setTitle("Winter Transfer Window Opens");
        winterOpen.setPriority(1);
        events.add(winterOpen);

        CalendarEvent winterClose = new CalendarEvent();
        winterClose.setSeason(season);
        winterClose.setDay(210);
        winterClose.setPhase("MORNING");
        winterClose.setEventType("TRANSFER_WINDOW_CLOSE");
        winterClose.setStatus("PENDING");
        winterClose.setTitle("Winter Transfer Window Closes");
        winterClose.setPriority(1);
        events.add(winterClose);

        return events;
    }

    /**
     * Generates season boundary events.
     */
    private List<CalendarEvent> generateSeasonBoundaryEvents(int season) {
        List<CalendarEvent> events = new ArrayList<>();

        CalendarEvent seasonStart = new CalendarEvent();
        seasonStart.setSeason(season);
        seasonStart.setDay(1);
        seasonStart.setPhase("MORNING");
        seasonStart.setEventType("SEASON_START");
        seasonStart.setStatus("PENDING");
        seasonStart.setTitle("Season " + season + " Begins");
        seasonStart.setPriority(1);
        events.add(seasonStart);

        CalendarEvent seasonEnd = new CalendarEvent();
        seasonEnd.setSeason(season);
        seasonEnd.setDay(340);
        seasonEnd.setPhase("EVENING");
        seasonEnd.setEventType("SEASON_END");
        seasonEnd.setStatus("PENDING");
        seasonEnd.setTitle("Season " + season + " Ends");
        seasonEnd.setPriority(1);
        events.add(seasonEnd);

        CalendarEvent awards = new CalendarEvent();
        awards.setSeason(season);
        awards.setDay(335);
        awards.setPhase("EVENING");
        awards.setEventType("AWARDS_CEREMONY");
        awards.setStatus("PENDING");
        awards.setTitle("End of Season Awards Ceremony");
        awards.setPriority(1);
        events.add(awards);

        CalendarEvent contractCheck = new CalendarEvent();
        contractCheck.setSeason(season);
        contractCheck.setDay(345);
        contractCheck.setPhase("MORNING");
        contractCheck.setEventType("CONTRACT_EXPIRY_CHECK");
        contractCheck.setStatus("PENDING");
        contractCheck.setTitle("Contract Expiry Check");
        contractCheck.setPriority(1);
        events.add(contractCheck);

        CalendarEvent seasonTransition = new CalendarEvent();
        seasonTransition.setSeason(season);
        seasonTransition.setDay(360);
        seasonTransition.setPhase("EVENING");
        seasonTransition.setEventType("SEASON_TRANSITION");
        seasonTransition.setStatus("PENDING");
        seasonTransition.setTitle("New Season Preparation");
        seasonTransition.setPriority(1);
        events.add(seasonTransition);

        return events;
    }

    /**
     * Updates all CompetitionTeamInfoMatch entries for a competition with
     * the corresponding calendar day for each matchday/round.
     */
    private void updateMatchDays(long competitionId, int season, int[] matchDays) {
        for (int i = 0; i < matchDays.length; i++) {
            int competitionMatchday = i + 1;
            int calendarDay = matchDays[i];
            assignMatchDay(competitionId, season, competitionMatchday, calendarDay);
        }
    }

    /**
     * Finds all CompetitionTeamInfoMatch entries for the given competition, round, and season,
     * then sets their day field to the specified calendar day.
     */
    public void assignMatchDay(long competitionId, int season, int competitionMatchday, int calendarDay) {
        String seasonNumber = String.valueOf(season);
        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(competitionId, competitionMatchday, seasonNumber);

        for (CompetitionTeamInfoMatch match : matches) {
            match.setDay(calendarDay);
        }

        if (!matches.isEmpty()) {
            competitionTeamInfoMatchRepository.saveAll(matches);
        }
    }
}
