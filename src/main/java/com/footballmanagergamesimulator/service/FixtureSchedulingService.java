package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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

    @Autowired
    @Lazy
    private FriendlyMatchService friendlyMatchService;

    @Autowired
    private RoundRobin roundRobin;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    @Lazy
    private EuropeanCompetitionService europeanCompetitionService;

    @Autowired
    private com.footballmanagergamesimulator.config.CompetitionFormatConfig competitionFormat;

    private String currentSeasonStr() {
        return roundRepository.findById(1L).map(Round::getSeason).map(String::valueOf).orElse("1");
    }

    /**
     * Generates league fixtures (round-robin for league/second league) OR draws
     * knockout pairings (cup/European) for one competition+round, persisting the
     * resulting CompetitionTeamInfoMatch rows. League fixtures are generated for
     * the full season in one shot on round 1; knockouts are drawn round-by-round
     * with seeded variants for LoC preliminary/qualifying and Stars Cup playoff.
     * Guarded against duplicate fixture generation (idempotent on re-entry).
     */
    public void getFixturesForRound(String competitionId, String roundId) {
        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        String currentSeasonStr = currentSeasonStr();

        List<Long> participants = getParticipants(_competitionId, _roundId, currentSeasonStr);

        Set<Long> leagueCompetitionIds = competitionIdsByType(1);
        Set<Long> secondLeagueCompetitionIds = competitionIdsByType(3);

        if (leagueCompetitionIds.contains(_competitionId) || secondLeagueCompetitionIds.contains(_competitionId)) {
            // Guard: league fixtures are generated once per season (all rounds at once)
            // from the round=1 entry point. Skip if any round already exists for this
            // competition+season to prevent duplicating the entire schedule.
            List<Long> existingRounds = competitionTeamInfoMatchRepository
                    .findDistinctRoundsByCompetitionIdAndSeasonNumber(_competitionId, currentSeasonStr);
            if (!existingRounds.isEmpty()) {
                System.out.println("=== getFixturesForRound league: comp=" + competitionId
                        + " season=" + currentSeasonStr + " already has " + existingRounds.size()
                        + " rounds, skipping");
                return;
            }

            // One pass over the (double round-robin) schedule = 2 encounters. The
            // number of encounters per pair is driven by the competition format,
            // keyed by team count (single source, shared with the test engine). Odd
            // encounter counts add one extra single round-robin (first-listed team home).
            List<List<List<Long>>> schedule = roundRobin.getSchedule(participants);
            int currentRound = 1;

            int typeId = competitionRepository.findById(_competitionId)
                    .map(c -> (int) c.getTypeId()).orElse(1);
            int encounters = competitionFormat.get(typeId).encountersFor(participants.size());
            int fullPasses = encounters / 2;
            boolean extraSingle = (encounters % 2) == 1;

            boolean reverse = true;

            for (int i = 0; i < fullPasses; i++) {
                for (List<List<Long>> round : schedule) {
                    reverse = !reverse;
                    for (List<Long> match : round) {
                        long teamHomeId = match.get(0);
                        long teamAwayId = match.get(1);

                        CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                        competitionTeamInfoMatch.setCompetitionId(_competitionId);
                        competitionTeamInfoMatch.setRound(currentRound);
                        if (reverse) {
                            competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                            competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                        } else {
                            competitionTeamInfoMatch.setTeam1Id(teamAwayId);
                            competitionTeamInfoMatch.setTeam2Id(teamHomeId);
                        }
                        competitionTeamInfoMatch.setSeasonNumber(currentSeasonStr);
                        competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
                    }
                    currentRound++;
                }
            }

            if (extraSingle) {
                // Extra meeting for odd encounters: one single round-robin (the schedule's
                // first leg), first-listed team hosts — deterministic alternating home rule.
                List<List<List<Long>>> firstLeg = schedule.subList(0, schedule.size() / 2);
                for (List<List<Long>> round : firstLeg) {
                    for (List<Long> match : round) {
                        CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                        competitionTeamInfoMatch.setCompetitionId(_competitionId);
                        competitionTeamInfoMatch.setRound(currentRound);
                        competitionTeamInfoMatch.setTeam1Id(match.get(0));
                        competitionTeamInfoMatch.setTeam2Id(match.get(1));
                        competitionTeamInfoMatch.setSeasonNumber(currentSeasonStr);
                        competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
                    }
                    currentRound++;
                }
            }

        } else {
            // Guard: skip if fixtures for this knockout round already exist (prevents duplicates)
            List<CompetitionTeamInfoMatch> existingKnockout = competitionTeamInfoMatchRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(_competitionId, _roundId, currentSeasonStr);
            if (!existingKnockout.isEmpty()) {
                System.out.println("=== getFixturesForRound knockout: comp=" + competitionId + " round=" + roundId + " already drawn, skipping");
                return;
            }

            System.out.println("=== getFixturesForRound knockout: comp=" + competitionId + " round=" + roundId + " participants=" + participants.size());

            Set<Long> locIdsForDraw = competitionIdsByType(4);
            Set<Long> starsCupIdsForDraw = competitionIdsByType(5);

            if (locIdsForDraw.contains(_competitionId)
                    && competitionFormat.get(4).isSeededKnockoutDrawRound(_roundId)) {
                europeanCompetitionService.drawEuropeanKnockoutSeeded(_competitionId, _roundId, participants);
                return;
            }
            if (starsCupIdsForDraw.contains(_competitionId)
                    && competitionFormat.get(5).isSeededKnockoutDrawRound(_roundId)) {
                europeanCompetitionService.drawStarsCupPlayoffSeeded(_competitionId, _roundId, participants);
                return;
            }

            Collections.shuffle(participants);

            // If odd number of participants, give the last team a bye to next round
            if (participants.size() % 2 != 0) {
                long byeTeamId = participants.remove(participants.size() - 1);
                long nextRoundForBye = _roundId + 1;
                CompetitionTeamInfo byeEntry = new CompetitionTeamInfo();
                byeEntry.setTeamId(byeTeamId);
                byeEntry.setCompetitionId(_competitionId);
                byeEntry.setSeasonNumber(Long.parseLong(currentSeasonStr));
                byeEntry.setRound(nextRoundForBye);
                competitionTeamInfoRepository.save(byeEntry);
            }

            for (int i = 0; i < participants.size(); i += 2) {
                long teamHomeId = participants.get(i);
                long teamAwayId = participants.get(i + 1);

                CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                competitionTeamInfoMatch.setCompetitionId(_competitionId);
                competitionTeamInfoMatch.setRound(_roundId);
                competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                competitionTeamInfoMatch.setSeasonNumber(currentSeasonStr);
                competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
            }
        }
    }

    private List<Long> getParticipants(long competitionId, long roundId, String seasonStr) {
        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(roundId, competitionId, Long.parseLong(seasonStr));
        return new ArrayList<>(competitionTeamInfos.stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .collect(Collectors.toSet()));
    }

    private Set<Long> competitionIdsByType(int competitionTypeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == competitionTypeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

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
                        // Fallback: estimate from team count using the competition format
                        // (same encounters source as fixture generation).
                        int numTeams = getTeamCountForCompetition(comp.getId(), season);
                        int encounters = competitionFormat.get((int) comp.getTypeId()).encountersFor(numTeams);
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
                    // Matchday count comes from the derived format plan so a shape
                    // change (totalTeams/groups/qualifyPerGroup) adapts the calendar.
                    // Default 40/4×4 = 11 rounds: 0-1 preliminary, 2-7 groups, 8-10 KO.
                    com.footballmanagergamesimulator.config.EuropeanFormatPlan locPlan =
                            competitionFormat.get(4).europeanPlan();
                    int locRounds = locPlan != null ? locPlan.totalRounds() : 11;
                    matchDays = locRounds == 11
                            ? new int[]{28, 38, 56, 77, 105, 140, 168, 196, 245, 280, 330}
                            : generateEuropeanMatchDays(locRounds, 28, 330);
                    eventType = "MATCH_EUROPEAN";
                    break;
                case 5: // Stars Cup (with group stage)
                    // Matchday count = round count, derived from the format's shape
                    // (1-based contiguous rounds 1..finalRound). Default 4×4 = 10
                    // rounds: 1-6 groups, 7 playoff, 8-10 QF/SF/Final.
                    int scRounds = competitionFormat.get(5).finalRound();
                    matchDays = scRounds == 10
                            ? new int[]{42, 60, 84, 112, 147, 175, 220, 252, 295, 325}
                            : generateEuropeanMatchDays(scRounds, 42, 325);
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

        // 2. Auto-schedule pre-season friendlies with real opponents for human teams
        // Create MATCH_FRIENDLY calendar events on the standard pre-season days
        int[] friendlyDays = {7, 14, 21, 28};
        for (int i = 0; i < friendlyDays.length; i++) {
            int day = friendlyDays[i];
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

        // After saving all events, auto-schedule friendly matches with opponents
        // (done after bulk save below since we need the calendar events saved first)

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

        // Auto-schedule pre-season friendlies with real opponents for human teams
        friendlyMatchService.autoSchedulePreSeasonFriendlies(season, allMatchDays);
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
     * Spreads {@code numMatchdays} European matchdays evenly across [startDay, endDay].
     * Used when a configurable LoC shape produces a round count other than the
     * default 11; keeps each matchday unique and at least 3 days apart.
     */
    private int[] generateEuropeanMatchDays(int numMatchdays, int startDay, int endDay) {
        int[] days = new int[numMatchdays];
        double interval = (double) (endDay - startDay) / Math.max(1, numMatchdays - 1);
        for (int i = 0; i < numMatchdays; i++) {
            days[i] = Math.min(startDay + (int) Math.round(i * interval), endDay);
        }
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
     * Re-sync calendar days onto already-existing CompetitionTeamInfoMatch rows of one
     * competition, using whatever CalendarEvent rows already exist for that competition+season.
     *
     * Needed for cups: generateSeasonCalendar() runs BEFORE CupBracketService creates the
     * bracket matches, so its updateMatchDays() finds nothing to update and the cup matches
     * end up with day=0. Calling this after generateBracket() fixes that without rebuilding
     * the calendar.
     */
    public void syncCalendarDaysOntoExistingMatches(long competitionId, int season) {
        List<CalendarEvent> events = calendarEventRepository.findAllBySeasonAndStatus(season, "PENDING").stream()
                .filter(e -> e.getCompetitionId() != null && e.getCompetitionId() == competitionId
                        && e.getMatchday() > 0
                        && ("MATCH_LEAGUE".equals(e.getEventType())
                            || "MATCH_CUP".equals(e.getEventType())
                            || "MATCH_EUROPEAN".equals(e.getEventType())))
                .toList();
        for (CalendarEvent e : events) {
            assignMatchDay(competitionId, season, e.getMatchday(), e.getDay());
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
