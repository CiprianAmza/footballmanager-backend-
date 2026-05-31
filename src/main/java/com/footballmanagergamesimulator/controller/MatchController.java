package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.*;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.GoalAnimationService;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import com.footballmanagergamesimulator.service.MatchService;
import com.footballmanagergamesimulator.service.PressConferenceService;
import com.footballmanagergamesimulator.service.SuspensionService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
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
    LiveMatchSimulationService liveMatchSimulationService;
    @Autowired
    CompetitionController competitionController;
    @Autowired
    PressConferenceService pressConferenceService;
    @Autowired
    SuspensionService suspensionService;
    @Autowired
    UserContext userContext;
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
    MatchPlayerRatingRepository matchPlayerRatingRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    GoalAnimationService goalAnimationService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationService matchSimulationService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchStatsService matchStatsService;
    @Autowired
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired
    com.footballmanagergamesimulator.service.GameStateService gameStateService;

    @GetMapping("/getScheduleForSeasonNumber/{seasonNumber}/{teamId}")
    public List<ScheduleView> getScheduleForSeasonNumberAndTeamId(@PathVariable(name = "seasonNumber") int seasonNumber, @PathVariable(name = "teamId") long teamId) {

        List<CompetitionTeamInfoMatch> competitionTeamInfoMatches = competitionTeamInfoMatchRepository.findAllBySeasonNumberAndTeamId(String.valueOf(seasonNumber), teamId);

        return matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(competitionTeamInfoMatches, teamId, seasonNumber);
    }

    @GetMapping("/getScheduleForCurrentSeasonAndTeamId/{teamId}")
    public List<ScheduleView> getScheduleForCurrentSeasonAndTeamId(@PathVariable(name = "teamId") long teamId) {

        long currentSeason = gameStateService.currentSeason();
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

        long currentSeason = gameStateService.currentSeason();
        List<CompetitionTeamInfoMatch> allMatches = competitionTeamInfoMatchRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(currentSeason), teamId);

        // Find the first match that has no result yet (no CompetitionTeamInfoDetail)
        // Sort by calendar day to find the chronologically next match across all competitions
        CompetitionTeamInfoMatch nextMatch = null;
        for (CompetitionTeamInfoMatch match : allMatches.stream()
                .sorted(Comparator.comparingInt(CompetitionTeamInfoMatch::getDay))
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

        // Use real match stats if available, otherwise estimate from power ratio
        Optional<com.footballmanagergamesimulator.model.MatchStats> matchStats =
                matchStatsService.getMatchStats(competitionId, season, round, teamId1, teamId2);
        if (matchStats.isPresent()) {
            summary.setHomePossession(matchStats.get().getHomePossession());
            summary.setAwayPossession(matchStats.get().getAwayPossession());
        } else {
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
        }

        return summary;
    }

    /**
     * Per-player lineup ratings for a single match, as computed by LineupRatingService at
     * simulate/commit time (morale/fitness/familiarity/role/instruction baked in). NOTE: this is
     * NOT the 0-10 Scorer performance rating shown in /summary — it is the attribute-weighted
     * lineup rating (typically in the hundreds). Each entry carries the player's age + nation.
     */
    @GetMapping("/playerRatings/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public MatchLineupRatingView getMatchPlayerRatings(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") int season,
            @PathVariable(name = "round") int round,
            @PathVariable(name = "teamId1") long teamId1,
            @PathVariable(name = "teamId2") long teamId2) {

        MatchLineupRatingView view = new MatchLineupRatingView();
        view.setHomeTeamId(teamId1);
        view.setAwayTeamId(teamId2);
        view.setHomeTeamName(teamRepository.findNameById(teamId1));
        view.setAwayTeamName(teamRepository.findNameById(teamId2));

        Map<Long, String> nationNameCache = new HashMap<>();
        view.setHomeLineup(toLineup(competitionId, season, round, teamId1, nationNameCache));
        view.setAwayLineup(toLineup(competitionId, season, round, teamId2, nationNameCache));
        return view;
    }

    private List<MatchLineupRatingView.PlayerLine> toLineup(
            long competitionId, int season, int round, long teamId, Map<Long, String> nationNameCache) {

        List<MatchPlayerRating> rows = matchPlayerRatingRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(competitionId, season, round, teamId);

        return rows.stream().map(r -> {
            MatchLineupRatingView.PlayerLine line = new MatchLineupRatingView.PlayerLine();
            line.setPlayerId(r.getPlayerId());
            line.setPlayerName(r.getPlayerName());
            line.setPosition(r.getPosition());
            line.setRating(r.getRating());
            line.setAge(r.getAge());
            line.setNationId(r.getNationId());
            line.setNationName(nationNameCache.computeIfAbsent(r.getNationId(), this::resolveNationName));
            return line;
        }).collect(Collectors.toList());
    }

    /** Nation name = first word of that nation's League competition name; "International" fallback. */
    private String resolveNationName(long nationId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1 && c.getNationId() == nationId)
                .findFirst()
                .map(c -> c.getName().split(" ")[0])
                .orElse("International");
    }

    /**
     * Fetch the live match simulation timeline by key.
     * Key format: competitionId_season_round_teamId1_teamId2
     */
    @GetMapping("/live/{key}")
    public LiveMatchData getLiveMatch(@PathVariable String key) {
        return liveMatchSimulationService.getLiveMatchData(key);
    }

    /**
     * Fetch the live match simulation timeline by match parameters.
     */
    @GetMapping("/live/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public LiveMatchData getLiveMatch(
            @PathVariable long competitionId,
            @PathVariable int season,
            @PathVariable int round,
            @PathVariable long teamId1,
            @PathVariable long teamId2) {
        return liveMatchSimulationService.getLiveMatchData(competitionId, season, round, teamId1, teamId2);
    }

    // ==================== INTERACTIVE LIVE MATCH (Faza 3) ====================

    /**
     * Read the current state of a live match session — minute, score,
     * timeline-so-far, pitch + bench lists with stamina, subs remaining.
     * Returns 404 if the session is gone (e.g. cache evicted, never started).
     */
    @GetMapping("/live/{key}/state")
    public org.springframework.http.ResponseEntity<LiveMatchData> getLiveMatchState(@PathVariable String key) {
        LiveMatchSession session = liveMatchSimulationService.getSession(key);
        if (session == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok(session.snapshot());
    }

    /**
     * Advance the session up to the given minute (clamped to full time).
     * Returns the new state including any events that fired during this tick.
     */
    @PostMapping("/live/{key}/advance")
    public org.springframework.http.ResponseEntity<LiveMatchData> advanceLiveMatch(
            @PathVariable String key,
            @RequestParam("untilMinute") int untilMinute) {
        LiveMatchSession session = liveMatchSimulationService.getSession(key);
        if (session == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok(session.advanceUntilAndSnapshot(untilMinute));
    }

    /**
     * Apply a manager-driven substitution to the session. Body is a tiny
     * {playerOutId, playerInId} pair. Returns 400 with a human-readable error
     * if the swap is illegal (subs exhausted, GK mismatch, wrong team, etc.).
     */
    @PostMapping("/live/{key}/substitute")
    public org.springframework.http.ResponseEntity<?> substituteInLiveMatch(
            @PathVariable String key,
            @RequestBody SubstituteRequest body) {
        LiveMatchSession session = liveMatchSimulationService.getSession(key);
        if (session == null) return org.springframework.http.ResponseEntity.notFound().build();
        try {
            LiveMatchData state = body.atMinute > 0
                    ? session.applyUserSubAtMinuteAndSnapshot(body.playerOutId, body.playerInId, body.atMinute)
                    : session.applyUserSubAndSnapshot(body.playerOutId, body.playerInId);
            return org.springframework.http.ResponseEntity.ok(state);
        } catch (LiveMatchSimulationService.InvalidSubstitutionException e) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** Body for POST /match/live/{key}/substitute. */
    public static class SubstituteRequest {
        public long playerOutId;
        public long playerInId;
        /** Optional — minute at which the sub should be recorded in the timeline. */
        public int atMinute;
    }

    /**
     * Finalize an interactive live match — runs the post-match work that was
     * deferred from {@code simulateMatchday} (scorers, stats, standings,
     * injuries, suspensions, news, post-match press conference). Idempotent.
     *
     * <p>Called by the frontend after polling {@code /advance} all the way to
     * full time. The response includes the match result + the post-match PC
     * id (when applicable) so the FE can chain straight into the PC modal.
     */
    @PostMapping("/live/{key}/commit")
    public org.springframework.http.ResponseEntity<?> commitLiveMatch(@PathVariable String key) {
        LiveMatchSession session = liveMatchSimulationService.getSession(key);
        if (session == null) return org.springframework.http.ResponseEntity.notFound().build();
        if (!session.isFinished()) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Match is still in progress."));
        }
        if (session.isCommitted()) {
            // Idempotent — return cached snapshot + minimal result block.
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of(
                    "alreadyCommitted", true,
                    "committed", true,
                    "homeScore", session.getHomeScore(),
                    "awayScore", session.getAwayScore()));
        }

        // Run the heavy post-match work via CompetitionController.
        Map<String, Object> result;
        try {
            result = matchSimulationOrchestrator.finalizeInteractiveLiveMatch(key);
        } catch (RuntimeException e) {
            return org.springframework.http.ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", e.getMessage()));
        }

        long compId = session.getCompetitionId();
        int matchday = session.getRound();
        int season = session.getSeason();

        // Suspensions for the competition+round (now that all scores + cards
        // are persisted for this match too).
        try { suspensionService.processMatchCards(compId, matchday, season); }
        catch (Exception ex) { /* non-fatal */ }

        // Post-match press conference for the manager that watched, if they
        // attend PCs and didn't delegate to the assistant. Same logic as
        // GameAdvanceService, but fires here so the FE chains it after the
        // match-result modal.
        long userTeamId = userTeamForSession(session);
        if (userTeamId > 0) {
            List<Human> humanManagers = humanRepository.findAllByTeamIdAndTypeId(userTeamId, TypeNames.MANAGER_TYPE);
            if (!humanManagers.isEmpty()
                    && humanManagers.get(0).isViewFullMatch()
                    && humanManagers.get(0).isAttendPressConferences()) {
                boolean isHome = session.getTeamId1() == userTeamId;
                int teamScore = isHome ? session.getHomeScore() : session.getAwayScore();
                int opponentScore = isHome ? session.getAwayScore() : session.getHomeScore();
                try {
                    PressConference postMatchPc = pressConferenceService.generatePostMatchPressConference(
                            userTeamId, compId, matchday, season, teamScore, opponentScore);
                    result.put("postMatchPressConferenceId", postMatchPc.getId());
                    result.put("postMatchPressConferenceOutcome",
                            teamScore > opponentScore ? "WIN"
                                    : teamScore < opponentScore ? "LOSS" : "DRAW");
                    // Boardroom dynamics: if the owner restricts this coach, raise the arrogance/
                    // humiliation question too (null when the owner imposes nothing).
                    PressConference boardroomPc = pressConferenceService.generateBoardroomPressConference(
                            userTeamId, season, matchday);
                    if (boardroomPc != null) {
                        result.put("boardroomPressConferenceId", boardroomPc.getId());
                    }
                } catch (Exception ex) { /* non-fatal */ }
            }
        }

        // Final live-match data so the FE has the canonical post-commit state.
        result.put("committed", true);
        result.put("liveMatch", session.snapshot());
        return org.springframework.http.ResponseEntity.ok(result);
    }

    /** Resolve which side of the session belongs to the human user. Returns
     *  team1 by default if userContext can't tell (interactive matches should
     *  always have at least one human side). */
    private long userTeamForSession(LiveMatchSession session) {
        if (userContext.isHumanTeam(session.getTeamId1())) return session.getTeamId1();
        if (userContext.isHumanTeam(session.getTeamId2())) return session.getTeamId2();
        return session.getTeamId1();
    }

    /**
     * Generate a single goal animation on demand for preview/testing.
     *
     * @param teamId1 attacking team (home)
     * @param teamId2 defending team (away)
     * @param type    OPEN_PLAY, PENALTY, or FREE_KICK
     * @param outcome GOAL, SAVE, or MISS (MISS only for FREE_KICK)
     * @param minute  simulated match minute (affects side mirroring)
     */
    @GetMapping("/animation/preview")
    public GoalAnimationData previewAnimation(
            @RequestParam long teamId1,
            @RequestParam long teamId2,
            @RequestParam(defaultValue = "OPEN_PLAY") String type,
            @RequestParam(defaultValue = "GOAL") String outcome,
            @RequestParam(defaultValue = "25") int minute) {

        List<Human> atkAll = humanRepository.findAllByTeamIdAndTypeId(teamId1, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());
        List<Human> defAll = humanRepository.findAllByTeamIdAndTypeId(teamId2, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());

        if (atkAll.isEmpty() || defAll.isEmpty()) return null;

        // Pick a random scorer from the attacking team (weighted by position)
        Random rng = new Random();
        List<Human> outfield = atkAll.stream()
                .filter(h -> !"GK".equals(h.getPosition()))
                .collect(Collectors.toList());
        if (outfield.isEmpty()) outfield = atkAll;
        Human scorer = outfield.get(rng.nextInt(outfield.size()));

        switch (type.toUpperCase()) {
            case "PENALTY":
                return goalAnimationService.generatePenalty(
                        atkAll, defAll, scorer,
                        teamId1, teamId2, teamId1, minute,
                        "GOAL".equalsIgnoreCase(outcome));

            case "FREE_KICK":
                String fkOutcome = outcome.toUpperCase();
                if (!"GOAL".equals(fkOutcome) && !"SAVE".equals(fkOutcome) && !"MISS".equals(fkOutcome)) {
                    fkOutcome = "GOAL";
                }
                return goalAnimationService.generateFreeKick(
                        atkAll, defAll, scorer,
                        teamId1, teamId2, teamId1, minute, fkOutcome);

            default: // OPEN_PLAY
                Human assister = null;
                if (outfield.size() > 1) {
                    do {
                        assister = outfield.get(rng.nextInt(outfield.size()));
                    } while (assister.getId() == scorer.getId());
                }
                return goalAnimationService.generate(
                        atkAll, defAll, scorer, assister,
                        teamId1, teamId2, teamId1, minute);
        }
    }

    /**
     * Simulate a full live match between two arbitrary teams on demand and
     * return the SAME {@link LiveMatchData} shape the in-game live-match view
     * consumes (timeline of minute events, running scoreline, both team names,
     * per-event commentary and goal animations). Used by the Animation Preview
     * page so it reflects the current live engine instead of single-clip frames.
     *
     * <p>Uses sentinel competition/season/round identifiers so the cached
     * session never collides with a real fixture.
     *
     * @param teamId1 home team
     * @param teamId2 away team
     */
    @GetMapping("/animation/livePreview")
    public LiveMatchData previewLiveMatch(
            @RequestParam long teamId1,
            @RequestParam long teamId2) {

        double power1 = calculateTeamPower(teamId1);
        double power2 = calculateTeamPower(teamId2);

        // Sentinel coordinates keep the preview session out of real fixtures'
        // key space (competitionId -1, season 0, round 0).
        return liveMatchSimulationService.simulateLiveMatch(
                teamId1, teamId2, power1, power2, -1L, 0, 0);
    }

    /**
     * Get full match statistics for a specific match.
     * Returns all Opta-style stats: possession, shots, passes, tackles, xG, etc.
     */
    @GetMapping("/stats/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public Map<String, Object> getMatchStats(
            @PathVariable long competitionId,
            @PathVariable int season,
            @PathVariable int round,
            @PathVariable long teamId1,
            @PathVariable long teamId2) {

        Optional<com.footballmanagergamesimulator.model.MatchStats> statsOpt =
                matchStatsService.getMatchStats(competitionId, season, round, teamId1, teamId2);

        if (statsOpt.isEmpty()) {
            return Map.of("available", false);
        }

        com.footballmanagergamesimulator.model.MatchStats s = statsOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("homeTeamName", teamRepository.findNameById(teamId1));
        result.put("awayTeamName", teamRepository.findNameById(teamId2));
        result.put("competitionName", competitionRepository.findNameById(competitionId));

        // Build stat rows as arrays [home, label, away] for easy frontend rendering
        List<Map<String, Object>> stats = new ArrayList<>();
        stats.add(statRow(s.getHomePossession() + "%", "Possession", s.getAwayPossession() + "%"));
        stats.add(statRow(s.getHomeShots(), "Total Shots", s.getAwayShots()));
        stats.add(statRow(s.getHomeShotsOnTarget(), "Shots on Target", s.getAwayShotsOnTarget()));
        stats.add(statRow(s.getHomeShotsBlocked(), "Shots Blocked", s.getAwayShotsBlocked()));
        stats.add(statRow(s.getHomeShots() - s.getHomeShotsOnTarget() - s.getHomeShotsBlocked(),
                "Shots Off Target",
                s.getAwayShots() - s.getAwayShotsOnTarget() - s.getAwayShotsBlocked()));
        stats.add(statRow(String.format("%.2f", s.getHomeXg() / 100.0), "Expected Goals (xG)",
                String.format("%.2f", s.getAwayXg() / 100.0)));
        stats.add(statRow(s.getHomeBigChances(), "Big Chances", s.getAwayBigChances()));
        stats.add(statRow(s.getHomeBigChancesMissed(), "Big Chances Missed", s.getAwayBigChancesMissed()));
        stats.add(statRow(s.getHomePasses(), "Passes", s.getAwayPasses()));
        stats.add(statRow(s.getHomePassAccuracy() + "%", "Pass Accuracy", s.getAwayPassAccuracy() + "%"));
        stats.add(statRow(s.getHomeCrosses(), "Crosses", s.getAwayCrosses()));
        stats.add(statRow(s.getHomeCrossesAccurate(), "Accurate Crosses", s.getAwayCrossesAccurate()));
        stats.add(statRow(s.getHomeCorners(), "Corners", s.getAwayCorners()));
        stats.add(statRow(s.getHomeOffsides(), "Offsides", s.getAwayOffsides()));
        stats.add(statRow(s.getHomeTackles(), "Tackles", s.getAwayTackles()));
        stats.add(statRow(s.getHomeInterceptions(), "Interceptions", s.getAwayInterceptions()));
        stats.add(statRow(s.getHomeClearances(), "Clearances", s.getAwayClearances()));
        stats.add(statRow(s.getHomeDuelsWon(), "Duels Won", s.getAwayDuelsWon()));
        stats.add(statRow(s.getHomeAerialDuelsWon(), "Aerial Duels Won", s.getAwayAerialDuelsWon()));
        stats.add(statRow(s.getHomeSaves(), "Saves", s.getAwaySaves()));
        stats.add(statRow(s.getHomeFouls(), "Fouls", s.getAwayFouls()));
        stats.add(statRow(s.getHomeFreeKicks(), "Free Kicks Won", s.getAwayFreeKicks()));
        stats.add(statRow(s.getHomeYellowCards(), "Yellow Cards", s.getAwayYellowCards()));
        stats.add(statRow(s.getHomeRedCards(), "Red Cards", s.getAwayRedCards()));

        result.put("stats", stats);

        // Also include raw numeric data for frontend charts
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("homePossession", s.getHomePossession());
        raw.put("awayPossession", s.getAwayPossession());
        raw.put("homeShots", s.getHomeShots());
        raw.put("awayShots", s.getAwayShots());
        raw.put("homeShotsOnTarget", s.getHomeShotsOnTarget());
        raw.put("awayShotsOnTarget", s.getAwayShotsOnTarget());
        raw.put("homeXg", s.getHomeXg() / 100.0);
        raw.put("awayXg", s.getAwayXg() / 100.0);
        raw.put("homePasses", s.getHomePasses());
        raw.put("awayPasses", s.getAwayPasses());
        raw.put("homePassAccuracy", s.getHomePassAccuracy());
        raw.put("awayPassAccuracy", s.getAwayPassAccuracy());
        raw.put("homeCorners", s.getHomeCorners());
        raw.put("awayCorners", s.getAwayCorners());
        raw.put("homeFouls", s.getHomeFouls());
        raw.put("awayFouls", s.getAwayFouls());
        raw.put("homeOffsides", s.getHomeOffsides());
        raw.put("awayOffsides", s.getAwayOffsides());
        raw.put("homeTackles", s.getHomeTackles());
        raw.put("awayTackles", s.getAwayTackles());
        raw.put("homeSaves", s.getHomeSaves());
        raw.put("awaySaves", s.getAwaySaves());
        raw.put("homeBigChances", s.getHomeBigChances());
        raw.put("awayBigChances", s.getAwayBigChances());
        result.put("raw", raw);

        return result;
    }

    /**
     * Get aggregated season statistics for a team.
     */
    @GetMapping("/stats/season/{teamId}/{season}")
    public Map<String, Object> getTeamSeasonStats(
            @PathVariable long teamId,
            @PathVariable int season) {
        return matchStatsService.getTeamSeasonStats(teamId, season);
    }

    private Map<String, Object> statRow(Object home, String label, Object away) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("home", home);
        row.put("label", label);
        row.put("away", away);
        return row;
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
