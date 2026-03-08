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
import java.util.stream.Collectors;

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
        Set<Integer> matchDaySet = new HashSet<>();
        List<CalendarEvent> allEvents = new ArrayList<>();

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
                        // Fallback: estimate from team count (4x round-robin)
                        int numTeams = getTeamCountForCompetition(comp.getId(), season);
                        numMatchdays = Math.max(2, 4 * (numTeams - 1));
                    }
                    matchDays = generateLeagueMatchDays(numMatchdays);
                    eventType = "MATCH_LEAGUE";
                    break;
                case 2: // Cup
                    matchDays = new int[]{49, 91, 154, 231, 280, 315};
                    eventType = "MATCH_CUP";
                    break;
                case 4: // LoC (Champions League equivalent)
                    matchDays = new int[]{38, 56, 77, 105, 140, 168, 196, 245, 280, 330};
                    eventType = "MATCH_EUROPEAN";
                    break;
                case 5: // Stars Cup
                    matchDays = new int[]{63, 133, 210, 308};
                    eventType = "MATCH_EUROPEAN";
                    break;
                default:
                    continue;
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

                matchDaySet.add(matchDays[i]);

                // Press conference the day before
                CalendarEvent pressConf = new CalendarEvent();
                pressConf.setSeason(season);
                pressConf.setDay(matchDays[i] - 1);
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

        // 2. Generate pre-season friendlies
        int[] friendlyDays = {7, 14, 21, 28};
        for (int day : friendlyDays) {
            CalendarEvent event = new CalendarEvent();
            event.setSeason(season);
            event.setDay(day);
            event.setPhase("EVENING");
            event.setEventType("MATCH_FRIENDLY");
            event.setStatus("PENDING");
            event.setTitle("Pre-season Friendly");
            event.setPriority(1);
            allEvents.add(event);

            matchDaySet.add(day);
        }

        // 3. Generate daily events (training and injury updates)
        Set<Integer> restDays = new HashSet<>();
        for (int matchDay : matchDaySet) {
            restDays.add(matchDay + 1);
        }

        for (int day = 1; day <= 365; day++) {
            // Training session (morning, skip match days and rest days)
            if (!matchDaySet.contains(day) && !restDays.contains(day)) {
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
     * Generates 38 league matchdays spread from day 35 to day 315,
     * snapped to the nearest weekend (Saturday = day%7==5, Sunday = day%7==6).
     */
    private int[] generateLeagueMatchDays(int numMatchdays) {
        int[] days = new int[numMatchdays];
        int startDay = 35;
        int endDay = 315;
        double interval = (double) (endDay - startDay) / (numMatchdays - 1);

        for (int i = 0; i < numMatchdays; i++) {
            int rawDay = startDay + (int) (i * interval);
            // Snap to nearest weekend (Saturday=day%7==5 or Sunday=day%7==6)
            int dayOfWeek = rawDay % 7;
            if (dayOfWeek < 5) {
                rawDay += (5 - dayOfWeek); // snap forward to Saturday
            }
            // dayOfWeek == 5 (Saturday) or 6 (Sunday) stays as is
            days[i] = Math.min(rawDay, endDay);
        }
        return days;
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
     * Summer window: days 1 (open) to 30 (close)
     * Winter window: days 201 (open) to 210 (close)
     */
    private List<CalendarEvent> generateTransferWindowEvents(int season) {
        List<CalendarEvent> events = new ArrayList<>();

        // Summer transfer window open - day 1
        CalendarEvent summerOpen = new CalendarEvent();
        summerOpen.setSeason(season);
        summerOpen.setDay(1);
        summerOpen.setPhase("MORNING");
        summerOpen.setEventType("TRANSFER_WINDOW_OPEN");
        summerOpen.setStatus("PENDING");
        summerOpen.setTitle("Summer Transfer Window Opens");
        summerOpen.setPriority(1);
        events.add(summerOpen);

        // Summer transfer window close - day 30
        CalendarEvent summerClose = new CalendarEvent();
        summerClose.setSeason(season);
        summerClose.setDay(30);
        summerClose.setPhase("MORNING");
        summerClose.setEventType("TRANSFER_WINDOW_CLOSE");
        summerClose.setStatus("PENDING");
        summerClose.setTitle("Summer Transfer Window Closes");
        summerClose.setPriority(1);
        events.add(summerClose);

        // Winter transfer window open - day 201
        CalendarEvent winterOpen = new CalendarEvent();
        winterOpen.setSeason(season);
        winterOpen.setDay(201);
        winterOpen.setPhase("MORNING");
        winterOpen.setEventType("TRANSFER_WINDOW_OPEN");
        winterOpen.setStatus("PENDING");
        winterOpen.setTitle("Winter Transfer Window Opens");
        winterOpen.setPriority(1);
        events.add(winterOpen);

        // Winter transfer window close - day 210
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
     * Generates season boundary events: season start, season end,
     * awards ceremony, and contract expiry check.
     */
    private List<CalendarEvent> generateSeasonBoundaryEvents(int season) {
        List<CalendarEvent> events = new ArrayList<>();

        // Season start - day 1
        CalendarEvent seasonStart = new CalendarEvent();
        seasonStart.setSeason(season);
        seasonStart.setDay(1);
        seasonStart.setPhase("MORNING");
        seasonStart.setEventType("SEASON_START");
        seasonStart.setStatus("PENDING");
        seasonStart.setTitle("Season " + season + " Begins");
        seasonStart.setPriority(1);
        events.add(seasonStart);

        // Season end - day 340
        CalendarEvent seasonEnd = new CalendarEvent();
        seasonEnd.setSeason(season);
        seasonEnd.setDay(340);
        seasonEnd.setPhase("EVENING");
        seasonEnd.setEventType("SEASON_END");
        seasonEnd.setStatus("PENDING");
        seasonEnd.setTitle("Season " + season + " Ends");
        seasonEnd.setPriority(1);
        events.add(seasonEnd);

        // Awards ceremony - day 335
        CalendarEvent awards = new CalendarEvent();
        awards.setSeason(season);
        awards.setDay(335);
        awards.setPhase("EVENING");
        awards.setEventType("AWARDS_CEREMONY");
        awards.setStatus("PENDING");
        awards.setTitle("End of Season Awards Ceremony");
        awards.setPriority(1);
        events.add(awards);

        // Contract expiry check - day 345
        CalendarEvent contractCheck = new CalendarEvent();
        contractCheck.setSeason(season);
        contractCheck.setDay(345);
        contractCheck.setPhase("MORNING");
        contractCheck.setEventType("CONTRACT_EXPIRY_CHECK");
        contractCheck.setStatus("PENDING");
        contractCheck.setTitle("Contract Expiry Check");
        contractCheck.setPriority(1);
        events.add(contractCheck);

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
    private void assignMatchDay(long competitionId, int season, int competitionMatchday, int calendarDay) {
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
