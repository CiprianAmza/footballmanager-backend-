package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.frontend.MatchPreviewView;
import com.footballmanagergamesimulator.frontend.MatchSummaryView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.MatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/match")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class MatchController {

    @Autowired
    CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    MatchService matchService;
    @Autowired
    CompetitionController competitionController;
    @Autowired
    MatchEventRepository matchEventRepository;
    @Autowired
    CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    HumanRepository humanRepository;

    @GetMapping("/getScheduleForSeasonNumber/{seasonNumber}/{teamId}")
    public List<ScheduleView> getScheduleForSeasonNumberAndTeamId(@PathVariable(name = "seasonNumber") int seasonNumber, @PathVariable(name = "teamId") long teamId) {

        List<CompetitionTeamInfoMatch> competitionTeamInfoMatches = competitionTeamInfoMatchRepository.findAllBySeasonNumberAndTeamId(String.valueOf(seasonNumber), teamId);

        return matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(competitionTeamInfoMatches, teamId, seasonNumber);
    }

    @GetMapping("/getScheduleForCurrentSeasonAndTeamId/{teamId}")
    public List<ScheduleView> getScheduleForCurrentSeasonAndTeamId(@PathVariable(name = "teamId") long teamId) {

        long currentSeason = Long.parseLong(competitionController.getCurrentSeason());
        List<CompetitionTeamInfoMatch> competitionTeamInfoMatches = competitionTeamInfoMatchRepository.findAllBySeasonNumberAndTeamId(String.valueOf(currentSeason), teamId);

        return matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(competitionTeamInfoMatches, teamId, currentSeason);
    }

    @GetMapping("/calendar/{teamId}/{season}")
    public List<CalendarEntryView> getCalendar(
            @PathVariable(name = "teamId") long teamId,
            @PathVariable(name = "season") int season) {

        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(season), teamId);

        return matchService.getCalendarEntries(matches, teamId, season);
    }

    @GetMapping("/matchEvents/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public List<MatchEvent> getMatchEvents(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") int season,
            @PathVariable(name = "round") int round,
            @PathVariable(name = "teamId1") long teamId1,
            @PathVariable(name = "teamId2") long teamId2) {

        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        competitionId, season, round, teamId1, teamId2);
        events.sort(Comparator.comparingInt(MatchEvent::getMinute));
        return events;
    }

    /**
     * Pre-match preview: returns the next upcoming match info for a team.
     */
    @GetMapping("/preview/{teamId}")
    public MatchPreviewView getMatchPreview(@PathVariable(name = "teamId") long teamId) {

        long currentSeason = Long.parseLong(competitionController.getCurrentSeason());
        List<CompetitionTeamInfoMatch> allMatches = competitionTeamInfoMatchRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(currentSeason), teamId);

        // Find the first match that has no result yet (no CompetitionTeamInfoDetail)
        CompetitionTeamInfoMatch nextMatch = null;
        for (CompetitionTeamInfoMatch match : allMatches.stream()
                .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound))
                .toList()) {

            CompetitionTeamInfoDetail detail = competitionTeamInfoDetailRepository
                    .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                            match.getCompetitionId(), match.getRound(),
                            match.getTeam1Id(), match.getTeam2Id(), currentSeason)
                    .stream().findFirst().orElse(null);

            if (detail == null) {
                nextMatch = match;
                break;
            }
        }

        if (nextMatch == null) {
            return null;
        }

        MatchPreviewView preview = new MatchPreviewView();
        preview.setHomeTeamId(nextMatch.getTeam1Id());
        preview.setAwayTeamId(nextMatch.getTeam2Id());
        preview.setHomeTeamName(teamRepository.findNameById(nextMatch.getTeam1Id()));
        preview.setAwayTeamName(teamRepository.findNameById(nextMatch.getTeam2Id()));
        preview.setCompetitionId(nextMatch.getCompetitionId());
        preview.setCompetitionName(competitionRepository.findNameById(nextMatch.getCompetitionId()));
        preview.setRound((int) nextMatch.getRound());

        // Form from TeamCompetitionDetail
        TeamCompetitionDetail homeDetail = teamCompetitionDetailRepository
                .findFirstByTeamIdAndCompetitionId(nextMatch.getTeam1Id(), nextMatch.getCompetitionId());
        TeamCompetitionDetail awayDetail = teamCompetitionDetailRepository
                .findFirstByTeamIdAndCompetitionId(nextMatch.getTeam2Id(), nextMatch.getCompetitionId());

        if (homeDetail != null) {
            preview.setHomeForm(homeDetail.getForm() != null ? homeDetail.getForm() : "");
        }
        if (awayDetail != null) {
            preview.setAwayForm(awayDetail.getForm() != null ? awayDetail.getForm() : "");
        }

        // League positions
        long competitionId = nextMatch.getCompetitionId();
        List<TeamCompetitionDetail> allTeams = teamCompetitionDetailRepository.findAll()
                .stream()
                .filter(d -> d.getCompetitionId() == competitionId)
                .sorted((a, b) -> {
                    if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                    if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                    return b.getGoalsFor() - a.getGoalsFor();
                })
                .toList();

        int homePos = 0, awayPos = 0;
        for (int i = 0; i < allTeams.size(); i++) {
            if (allTeams.get(i).getTeamId() == nextMatch.getTeam1Id()) homePos = i + 1;
            if (allTeams.get(i).getTeamId() == nextMatch.getTeam2Id()) awayPos = i + 1;
        }
        preview.setHomeLeaguePosition(homePos);
        preview.setAwayLeaguePosition(awayPos);

        // Head-to-head stats from match history
        List<CompetitionTeamInfoMatch> h2hMatches = competitionTeamInfoMatchRepository
                .findAllHeadToHead(nextMatch.getTeam1Id(), nextMatch.getTeam2Id());

        int homeWins = 0, awayWins = 0, draws = 0;
        for (CompetitionTeamInfoMatch h2h : h2hMatches) {
            CompetitionTeamInfoDetail h2hDetail = competitionTeamInfoDetailRepository
                    .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                            h2h.getCompetitionId(), h2h.getRound(),
                            h2h.getTeam1Id(), h2h.getTeam2Id(),
                            Long.parseLong(h2h.getSeasonNumber()))
                    .stream().findFirst().orElse(null);
            if (h2hDetail != null && h2hDetail.getScore() != null) {
                String[] parts = h2hDetail.getScore().split("-");
                if (parts.length == 2) {
                    try {
                        int score1 = Integer.parseInt(parts[0].trim());
                        int score2 = Integer.parseInt(parts[1].trim());
                        if (score1 > score2) {
                            // team1 won
                            if (h2h.getTeam1Id() == nextMatch.getTeam1Id()) homeWins++;
                            else awayWins++;
                        } else if (score2 > score1) {
                            if (h2h.getTeam2Id() == nextMatch.getTeam1Id()) homeWins++;
                            else awayWins++;
                        } else {
                            draws++;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        preview.setH2hHomeWins(homeWins);
        preview.setH2hAwayWins(awayWins);
        preview.setH2hDraws(draws);

        // Power rating based on average player ratings
        int homePower = calculateTeamPower(nextMatch.getTeam1Id());
        int awayPower = calculateTeamPower(nextMatch.getTeam2Id());
        preview.setHomePowerRating(homePower);
        preview.setAwayPowerRating(awayPower);

        // Prediction
        if (homePower > awayPower + 10) {
            preview.setPrediction("Home Win");
        } else if (awayPower > homePower + 10) {
            preview.setPrediction("Away Win");
        } else if (homePower > awayPower + 3) {
            preview.setPrediction("Slight Home Advantage");
        } else if (awayPower > homePower + 3) {
            preview.setPrediction("Slight Away Advantage");
        } else {
            preview.setPrediction("Even Match");
        }

        return preview;
    }

    /**
     * Post-match summary: returns detailed match summary with ratings, MOTM, possession.
     */
    @GetMapping("/summary/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public MatchSummaryView getMatchSummary(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") int season,
            @PathVariable(name = "round") int round,
            @PathVariable(name = "teamId1") long teamId1,
            @PathVariable(name = "teamId2") long teamId2) {

        MatchSummaryView summary = new MatchSummaryView();
        summary.setHomeTeamId(teamId1);
        summary.setAwayTeamId(teamId2);
        summary.setHomeTeamName(teamRepository.findNameById(teamId1));
        summary.setAwayTeamName(teamRepository.findNameById(teamId2));

        // Get the score
        CompetitionTeamInfoDetail detail = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                        competitionId, round, teamId1, teamId2, season)
                .stream().findFirst().orElse(null);
        if (detail != null) {
            summary.setScore(detail.getScore());
        }

        // Goal scorers from MatchEvent
        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        competitionId, season, round, teamId1, teamId2);
        events.sort(Comparator.comparingInt(MatchEvent::getMinute));

        List<MatchSummaryView.GoalDetail> goals = new ArrayList<>();
        for (MatchEvent event : events) {
            if ("goal".equals(event.getEventType())) {
                MatchSummaryView.GoalDetail goal = new MatchSummaryView.GoalDetail();
                goal.setPlayerName(event.getPlayerName());
                goal.setPlayerId(event.getPlayerId());
                goal.setMinute(event.getMinute());
                goal.setDetails(event.getDetails());
                goal.setTeamId(event.getTeamId());
                goal.setTeamName(teamRepository.findNameById(event.getTeamId()));
                goals.add(goal);
            }
        }
        summary.setGoals(goals);

        // Player ratings from Scorer records
        List<Scorer> homeScorers = scorerRepository
                .findAllByCompetitionIdAndSeasonNumberAndTeamIdAndOpponentTeamId(
                        competitionId, season, teamId1, teamId2);
        List<Scorer> awayScorers = scorerRepository
                .findAllByCompetitionIdAndSeasonNumberAndTeamIdAndOpponentTeamId(
                        competitionId, season, teamId2, teamId1);

        List<MatchSummaryView.PlayerRating> homeRatings = homeScorers.stream()
                .map(s -> {
                    MatchSummaryView.PlayerRating pr = new MatchSummaryView.PlayerRating();
                    pr.setPlayerName(getPlayerName(s.getPlayerId()));
                    pr.setPlayerId(s.getPlayerId());
                    pr.setPosition(s.getPosition());
                    pr.setRating(s.getRating());
                    pr.setGoals(s.getGoals());
                    pr.setAssists(s.getAssists());
                    return pr;
                })
                .sorted(Comparator.comparingDouble(MatchSummaryView.PlayerRating::getRating).reversed())
                .collect(Collectors.toList());

        List<MatchSummaryView.PlayerRating> awayRatings = awayScorers.stream()
                .map(s -> {
                    MatchSummaryView.PlayerRating pr = new MatchSummaryView.PlayerRating();
                    pr.setPlayerName(getPlayerName(s.getPlayerId()));
                    pr.setPlayerId(s.getPlayerId());
                    pr.setPosition(s.getPosition());
                    pr.setRating(s.getRating());
                    pr.setGoals(s.getGoals());
                    pr.setAssists(s.getAssists());
                    return pr;
                })
                .sorted(Comparator.comparingDouble(MatchSummaryView.PlayerRating::getRating).reversed())
                .collect(Collectors.toList());

        summary.setHomePlayerRatings(homeRatings);
        summary.setAwayPlayerRatings(awayRatings);

        // Man of the match - highest rated player across both teams
        Scorer motm = null;
        for (Scorer s : homeScorers) {
            if (motm == null || s.getRating() > motm.getRating()) motm = s;
        }
        for (Scorer s : awayScorers) {
            if (motm == null || s.getRating() > motm.getRating()) motm = s;
        }
        if (motm != null) {
            summary.setManOfTheMatchName(getPlayerName(motm.getPlayerId()));
            summary.setManOfTheMatchPlayerId(motm.getPlayerId());
            summary.setManOfTheMatchRating(motm.getRating());
            summary.setManOfTheMatchTeamId(motm.getTeamId());
        }

        // Possession estimate based on team power ratio
        int homePower = calculateTeamPower(teamId1);
        int awayPower = calculateTeamPower(teamId2);
        int totalPower = homePower + awayPower;
        if (totalPower > 0) {
            int homePossession = (int) Math.round((double) homePower / totalPower * 100);
            summary.setHomePossession(homePossession);
            summary.setAwayPossession(100 - homePossession);
        } else {
            summary.setHomePossession(50);
            summary.setAwayPossession(50);
        }

        return summary;
    }

    private int calculateTeamPower(long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
        if (players.isEmpty()) return 50;
        return (int) players.stream()
                .mapToDouble(Human::getRating)
                .average()
                .orElse(50);
    }

    private String getPlayerName(long playerId) {
        return humanRepository.findById(playerId)
                .map(Human::getName)
                .orElse("Unknown");
    }
}
