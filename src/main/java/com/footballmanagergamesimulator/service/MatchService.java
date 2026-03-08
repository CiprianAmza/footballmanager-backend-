package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

                if (scheduleView.getHomeOrAway().equals("A")) {
                    String[] values = score.split("-");
                    ArrayUtils.reverse(values);
                    score = values[0] + "-" + values[1];
                }
            }
            scheduleView.setScore(score);

            // Use calendar day for proper date display instead of round number
            int matchDay = competitionTeamInfoMatch.getDay();
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

            long typeId = competitionRepository.findTypeIdById(match.getCompetitionId());
            entry.setCompetitionType(mapCompetitionType(typeId));

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
                    String[] values = score.split("-");
                    ArrayUtils.reverse(values);
                    adjustedScore = values[0] + "-" + values[1];
                }

                entry.setScore(adjustedScore);
                entry.setStatus("played");

                // Determine W/D/L
                String[] parts = adjustedScore.split("-");
                int teamGoals = Integer.parseInt(parts[0].trim());
                int oppGoals = Integer.parseInt(parts[1].trim());
                if (teamGoals > oppGoals) entry.setResultOutcome("W");
                else if (teamGoals < oppGoals) entry.setResultOutcome("L");
                else entry.setResultOutcome("D");
            } else {
                entry.setScore("-");
                entry.setStatus("upcoming");
                entry.setResultOutcome(null);
            }

            entries.add(entry);
        }

        // Sort by round number
        entries.sort(Comparator.comparingInt(CalendarEntryView::getRoundNumber));

        return entries;
    }

    private String abbreviateTeamName(String name) {
        if (name == null || name.isEmpty()) return "???";
        // Take first 3 characters, uppercase
        return name.substring(0, Math.min(3, name.length())).toUpperCase();
    }

    private String mapCompetitionType(long typeId) {
        // typeId 1 = League, 2 = Cup, 3 = Second League (treat as League), 4+ = European
        if (typeId == 1 || typeId == 3) return "League";
        if (typeId == 2) return "Cup";
        return "European";
    }
}
