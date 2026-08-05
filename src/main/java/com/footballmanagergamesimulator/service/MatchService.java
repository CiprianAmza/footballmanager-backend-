package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MatchService {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    CalendarService calendarService;
    @Autowired
    FriendlyMatchRepository friendlyMatchRepository;
    @Autowired
    FriendlyEventRepository friendlyEventRepository;

    public List<ScheduleView> getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List<CompetitionTeamInfoMatch> competitionTeamInfoMatches, long teamId, long seasonNumber) {

        List<ScheduleView> scheduleViews = new ArrayList<>();

        for (CompetitionTeamInfoMatch competitionTeamInfoMatch: competitionTeamInfoMatches) {

            ScheduleView scheduleView = new ScheduleView();
            long opponentTeamId = competitionTeamInfoMatch.getTeam1Id() == teamId ? competitionTeamInfoMatch.getTeam2Id() : competitionTeamInfoMatch.getTeam1Id();
            String opponentTeamName = teamRepository.findNameById(opponentTeamId);
            scheduleView.setOpponentTeam(opponentTeamName);

            String competitionName = competitionRepository.findNameById(competitionTeamInfoMatch.getCompetitionId());
            scheduleView.setCompetitionName(competitionName);

            String ownTeamName = teamRepository.findNameById(teamId);
            boolean isHome = competitionTeamInfoMatch.getTeam1Id() == teamId;
            scheduleView.setHomeOrAway(isHome ? "H" : "A");

            // Set team abbreviations (first 3 letters) for display
            if (isHome) {
                scheduleView.setHomeTeamAbbr(abbreviateTeamName(ownTeamName));
                scheduleView.setAwayTeamAbbr(abbreviateTeamName(opponentTeamName));
            } else {
                scheduleView.setHomeTeamAbbr(abbreviateTeamName(opponentTeamName));
                scheduleView.setAwayTeamAbbr(abbreviateTeamName(ownTeamName));
            }

            CompetitionTeamInfoDetail competitionTeamInfoDetail = competitionTeamInfoDetailRepository.findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(competitionTeamInfoMatch.getCompetitionId(), competitionTeamInfoMatch.getRound(), competitionTeamInfoMatch.getTeam1Id(), competitionTeamInfoMatch.getTeam2Id(), seasonNumber).stream().findFirst().orElse(null);
            String score = "-";
            if (competitionTeamInfoDetail != null) {
                score = competitionTeamInfoDetail.getScore();
                scheduleView.setWinnerTeamId(competitionTeamInfoDetail.getWinnerTeamId());
                scheduleView.setDecidedBy(competitionTeamInfoDetail.getDecidedBy());

                if (scheduleView.getHomeOrAway().equals("A")) {
                    score = reverseScore(score);
                }
            }
            scheduleView.setScore(score);

            // Use calendar day for proper date display instead of round number
            int matchDay = competitionTeamInfoMatch.getDay();
            scheduleView.setDay(matchDay);
            if (matchDay > 0) {
                scheduleView.setDate(calendarService.getDateDisplay(matchDay));
            } else {
                scheduleView.setDate("Matchday " + competitionTeamInfoMatch.getRound());
            }

            // Populate fields for match event lookup
            scheduleView.setCompetitionId(competitionTeamInfoMatch.getCompetitionId());
            scheduleView.setSeasonNumber((int) seasonNumber);
            scheduleView.setRoundNumber((int) competitionTeamInfoMatch.getRound());
            scheduleView.setTeamId1(competitionTeamInfoMatch.getTeam1Id());
            scheduleView.setTeamId2(competitionTeamInfoMatch.getTeam2Id());

            scheduleViews.add(scheduleView);
        }

        appendFriendlySchedule(scheduleViews, teamId, (int) seasonNumber);

        // Sort by calendar day (chronological order across all competitions)
        scheduleViews.sort(Comparator.comparingInt(ScheduleView::getDay));

        return scheduleViews;
    }

    public List<CalendarEntryView> getCalendarEntries(List<CompetitionTeamInfoMatch> matches, long teamId, long seasonNumber) {

        List<CalendarEntryView> entries = new ArrayList<>();

        for (CompetitionTeamInfoMatch match : matches) {

            CalendarEntryView entry = new CalendarEntryView();

            entry.setRoundNumber((int) match.getRound());
            entry.setCompetitionId(match.getCompetitionId());
            entry.setSeasonNumber((int) seasonNumber);
            entry.setTeamId1(match.getTeam1Id());
            entry.setTeamId2(match.getTeam2Id());

            // Competition name and type
            String competitionName = competitionRepository.findNameById(match.getCompetitionId());
            entry.setCompetitionName(competitionName);

            Long typeId = competitionRepository.findTypeIdById(match.getCompetitionId());
            entry.setCompetitionType(mapCompetitionType(typeId != null ? typeId : 0L));

            // Opponent
            long opponentTeamId = match.getTeam1Id() == teamId ? match.getTeam2Id() : match.getTeam1Id();
            String opponentTeamName = teamRepository.findNameById(opponentTeamId);
            entry.setOpponentTeamName(opponentTeamName);
            entry.setOpponentTeamId(opponentTeamId);

            // Home/Away
            boolean isHomeCalendar = match.getTeam1Id() == teamId;
            entry.setHomeOrAway(isHomeCalendar ? "H" : "A");

            // Team abbreviations
            String ownTeamNameCal = teamRepository.findNameById(teamId);
            if (isHomeCalendar) {
                entry.setHomeTeamAbbr(abbreviateTeamName(ownTeamNameCal));
                entry.setAwayTeamAbbr(abbreviateTeamName(opponentTeamName));
            } else {
                entry.setHomeTeamAbbr(abbreviateTeamName(opponentTeamName));
                entry.setAwayTeamAbbr(abbreviateTeamName(ownTeamNameCal));
            }

            // Calendar date display
            int matchDayCal = match.getDay();
            entry.setDay(matchDayCal);
            if (matchDayCal > 0) {
                entry.setDateDisplay(calendarService.getDateDisplay(matchDayCal));
            }

            // Score and result
            CompetitionTeamInfoDetail detail = competitionTeamInfoDetailRepository
                    .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                            match.getCompetitionId(), match.getRound(),
                            match.getTeam1Id(), match.getTeam2Id(), seasonNumber).stream().findFirst().orElse(null);

            if (detail != null && detail.getScore() != null && !detail.getScore().equals("-")) {
                String score = detail.getScore();
                String adjustedScore = score;

                // Adjust score so it's always from our team's perspective
                if (entry.getHomeOrAway().equals("A")) {
                    adjustedScore = reverseScore(score);
                }

                entry.setScore(adjustedScore);
                entry.setStatus("played");

                // Determine W/D/L
                Matcher scoreMatcher = SCORE_PATTERN.matcher(adjustedScore);
                int teamGoals = scoreMatcher.matches() ? Integer.parseInt(scoreMatcher.group(1)) : 0;
                int oppGoals = scoreMatcher.matches() ? Integer.parseInt(scoreMatcher.group(2)) : 0;
                if (detail.getWinnerTeamId() != null) {
                    entry.setResultOutcome(detail.getWinnerTeamId() == teamId ? "W" : "L");
                } else if (teamGoals > oppGoals) entry.setResultOutcome("W");
                else if (teamGoals < oppGoals) entry.setResultOutcome("L");
                else entry.setResultOutcome("D");
            } else {
                entry.setScore("-");
                entry.setStatus("upcoming");
                entry.setResultOutcome(null);
            }

            entries.add(entry);
        }

        appendFriendlyCalendar(entries, teamId, (int) seasonNumber);

        // Sort by calendar day (chronological order across all competitions)
        entries.sort(Comparator.comparingInt(CalendarEntryView::getDay));

        return entries;
    }

    private void appendFriendlySchedule(List<ScheduleView> schedule, long teamId, int season) {
        List<FriendlyMatch> friendlies = teamFriendlies(teamId, season);
        Map<Long, FriendlyEvent> events = friendlyEvents(friendlies);
        for (FriendlyMatch match : friendlies) {
            boolean home = match.getHomeTeamId() == teamId;
            String opponentName = home ? match.getAwayTeamName() : match.getHomeTeamName();
            ScheduleView view = new ScheduleView();
            view.setOpponentTeam(opponentName);
            view.setHomeOrAway(home ? "H" : "A");
            view.setHomeTeamAbbr(abbreviateTeamName(match.getHomeTeamName()));
            view.setAwayTeamAbbr(abbreviateTeamName(match.getAwayTeamName()));
            view.setCompetitionName(friendlyCompetitionName(match, events));
            view.setScore(friendlyScore(match, home));
            view.setDate(calendarService.getDateDisplay(match.getDay()));
            view.setCompetitionId(friendlyCompetitionId(match));
            view.setSeasonNumber(season);
            view.setRoundNumber(safeMatchId(match.getId()));
            view.setTeamId1(match.getHomeTeamId());
            view.setTeamId2(match.getAwayTeamId());
            view.setDay(match.getDay());
            if ("COMPLETED".equals(match.getStatus()) && match.getHomeGoals() != match.getAwayGoals()) {
                view.setWinnerTeamId(match.getHomeGoals() > match.getAwayGoals()
                        ? match.getHomeTeamId() : match.getAwayTeamId());
            }
            view.setDecidedBy("COMPLETED".equals(match.getStatus()) ? "FRIENDLY" : null);
            schedule.add(view);
        }
    }

    private void appendFriendlyCalendar(List<CalendarEntryView> entries, long teamId, int season) {
        List<FriendlyMatch> friendlies = teamFriendlies(teamId, season);
        Map<Long, FriendlyEvent> events = friendlyEvents(friendlies);
        for (FriendlyMatch match : friendlies) {
            boolean home = match.getHomeTeamId() == teamId;
            String opponentName = home ? match.getAwayTeamName() : match.getHomeTeamName();
            CalendarEntryView entry = new CalendarEntryView();
            entry.setRoundNumber(safeMatchId(match.getId()));
            entry.setCompetitionName(friendlyCompetitionName(match, events));
            entry.setCompetitionId(friendlyCompetitionId(match));
            entry.setCompetitionType("Friendly");
            entry.setOpponentTeamName(opponentName);
            entry.setOpponentTeamId(home ? match.getAwayTeamId() : match.getHomeTeamId());
            entry.setHomeOrAway(home ? "H" : "A");
            entry.setHomeTeamAbbr(abbreviateTeamName(match.getHomeTeamName()));
            entry.setAwayTeamAbbr(abbreviateTeamName(match.getAwayTeamName()));
            entry.setDateDisplay(calendarService.getDateDisplay(match.getDay()));
            entry.setTeamId1(match.getHomeTeamId());
            entry.setTeamId2(match.getAwayTeamId());
            entry.setSeasonNumber(season);
            entry.setDay(match.getDay());
            entry.setScore(friendlyScore(match, home));
            if ("COMPLETED".equals(match.getStatus())) {
                entry.setStatus("played");
                int ownGoals = home ? match.getHomeGoals() : match.getAwayGoals();
                int opponentGoals = home ? match.getAwayGoals() : match.getHomeGoals();
                entry.setResultOutcome(ownGoals > opponentGoals ? "W" : ownGoals < opponentGoals ? "L" : "D");
            } else {
                entry.setStatus("upcoming");
                entry.setResultOutcome(null);
            }
            entries.add(entry);
        }
    }

    private List<FriendlyMatch> teamFriendlies(long teamId, int season) {
        return friendlyMatchRepository
                .findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(season, teamId, season, teamId).stream()
                .filter(match -> !"CANCELLED".equals(match.getStatus()))
                .toList();
    }

    private Map<Long, FriendlyEvent> friendlyEvents(List<FriendlyMatch> matches) {
        List<Long> ids = matches.stream().map(FriendlyMatch::getFriendlyEventId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, FriendlyEvent> events = new HashMap<>();
        friendlyEventRepository.findAllById(ids).forEach(event -> events.put(event.getId(), event));
        return events;
    }

    private String friendlyCompetitionName(FriendlyMatch match, Map<Long, FriendlyEvent> events) {
        FriendlyEvent event = match.getFriendlyEventId() == null ? null : events.get(match.getFriendlyEventId());
        if (event == null) return "Friendly";
        String stage = match.getEventStage() == null ? "" : " · " + match.getEventStage().replace('_', ' ');
        return event.getName() + stage;
    }

    private long friendlyCompetitionId(FriendlyMatch match) {
        return match.getFriendlyEventId() == null ? -1L : -Math.max(1L, match.getFriendlyEventId());
    }

    private int safeMatchId(long matchId) {
        return matchId > Integer.MAX_VALUE ? (int) (matchId % Integer.MAX_VALUE) : (int) matchId;
    }

    private String friendlyScore(FriendlyMatch match, boolean home) {
        if (!"COMPLETED".equals(match.getStatus())) return "-";
        return home ? match.getHomeGoals() + " - " + match.getAwayGoals()
                : match.getAwayGoals() + " - " + match.getHomeGoals();
    }

    private String abbreviateTeamName(String name) {
        if (name == null || name.isEmpty()) return "???";
        // Take first 3 characters, uppercase
        return name.substring(0, Math.min(3, name.length())).toUpperCase();
    }

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("^\\s*(\\d+)\\s*-\\s*(\\d+)(.*)$");

    private String reverseScore(String score) {
        Matcher matcher = SCORE_PATTERN.matcher(score);
        if (!matcher.matches()) return score;
        return matcher.group(2) + " - " + matcher.group(1) + matcher.group(3);
    }

    private String mapCompetitionType(long typeId) {
        // typeId 1 = League, 2 = Cup, 3 = Second League (treat as League), 4+ = European
        if (typeId == 1 || typeId == 3) return "League";
        if (typeId == 2) return "Cup";
        return "European";
    }
}
