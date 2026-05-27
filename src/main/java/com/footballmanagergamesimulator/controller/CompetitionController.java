package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.algorithms.*;
import com.footballmanagergamesimulator.frontend.*;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.*;
import com.footballmanagergamesimulator.user.*;
import com.footballmanagergamesimulator.util.*;
import com.footballmanagergamesimulator.service.*;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CompetitionController {

    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository;
    private final CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;

    final CompetitionRepository competitionRepository;
    final TeamFacilitiesRepository _teamFacilitiesRepository;
    final TacticService tacticService;
    final RoundRepository roundRepository;
    final ScorerRepository scorerRepository;
    final ScorerLeaderboardRepository scorerLeaderboardRepository;
    final SquadGenerationService squadGenerationService;
    final EuropeanCompetitionService europeanCompetitionService;
    final TransferMarketService transferMarketService;
    final SeasonObjectiveService seasonObjectiveService;
    final BootstrapService bootstrapService;
    final CompositeNameGenerator compositeNameGenerator;
    final FixtureSchedulingService fixtureSchedulingService;
    final UserContext userContext;
    @Lazy
    final StaffService staffService;
    @Lazy
    final SeasonTransitionService seasonTransitionService;
    final MatchSimulationOrchestrator matchSimulationOrchestrator;
    final JobOfferService jobOfferService;
    final CompetitionDisplayService competitionDisplayService;
    final CupBracketService cupBracketService;
    final GameInitializationService gameInitializationService;

    Round round;

    public CompetitionController(TeamFacilitiesRepository _teamFacilitiesRepository, TeamRepository teamRepository, HumanRepository humanRepository, CompetitionTeamInfoRepository competitionTeamInfoRepository, BootstrapService bootstrapService, CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository, CompetitionRepository competitionRepository, TacticService tacticService, MatchSimulationOrchestrator matchSimulationOrchestrator, RoundRepository roundRepository, ScorerRepository scorerRepository, SeasonTransitionService seasonTransitionService, ScorerLeaderboardRepository scorerLeaderboardRepository, FixtureSchedulingService fixtureSchedulingService, CompositeNameGenerator compositeNameGenerator, TransferMarketService transferMarketService, SeasonObjectiveService seasonObjectiveService, SquadGenerationService squadGenerationService, UserContext userContext, EuropeanCompetitionService europeanCompetitionService, StaffService staffService, CompetitionDisplayService competitionDisplayService, CupBracketService cupBracketService, JobOfferService jobOfferService, GameInitializationService gameInitializationService) {

        this._teamFacilitiesRepository = _teamFacilitiesRepository;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.competitionTeamInfoRepository = competitionTeamInfoRepository;
        this.bootstrapService = bootstrapService;
        this.competitionTeamInfoDetailRepository = competitionTeamInfoDetailRepository;
        this.competitionRepository = competitionRepository;
        this.tacticService = tacticService;
        this.matchSimulationOrchestrator = matchSimulationOrchestrator;
        this.roundRepository = roundRepository;
        this.scorerRepository = scorerRepository;
        this.seasonTransitionService = seasonTransitionService;
        this.scorerLeaderboardRepository = scorerLeaderboardRepository;
        this.fixtureSchedulingService = fixtureSchedulingService;
        this.compositeNameGenerator = compositeNameGenerator;
        this.transferMarketService = transferMarketService;
        this.seasonObjectiveService = seasonObjectiveService;
        this.squadGenerationService = squadGenerationService;
        this.userContext = userContext;
        this.europeanCompetitionService = europeanCompetitionService;
        this.staffService = staffService;
        this.competitionDisplayService = competitionDisplayService;
        this.cupBracketService = cupBracketService;
        this.jobOfferService = jobOfferService;
        this.gameInitializationService = gameInitializationService;
    }

    /** Exposes the cached {@code round} field for the season-transition service.
     *  Services hold mutated state through this reference so {@link #getCurrentSeason()}
     *  (which reads the field directly) stays in sync with what they save. */
    public Round getRoundCache() {
        return round;
    }

    // managerFired is now per-user on User.fired, not a global boolean

    // Cached competition type ID sets to avoid repeated DB queries
    private Set<Long> cachedLeagueCompIds = null;
    private Set<Long> cachedCupCompIds = null;
    private Set<Long> cachedSecondLeagueCompIds = null;

    /** Lazily populated competition-type ID caches — exposed so
     *  {@link com.footballmanagergamesimulator.service.MatchSimulationOrchestrator}
     *  can share the same warm cache instead of re-querying. */
    public Set<Long> getLeagueCompetitionIdsCached() {
        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = getCompetitionIdsByCompetitionType(1);
        }
        return cachedLeagueCompIds;
    }

    public Set<Long> getCupCompetitionIdsCached() {
        if (cachedCupCompIds == null) {
            cachedCupCompIds = getCompetitionIdsByCompetitionType(2);
        }
        return cachedCupCompIds;
    }

    public Set<Long> getSecondLeagueCompetitionIdsCached() {
        if (cachedSecondLeagueCompIds == null) {
            cachedSecondLeagueCompIds = getCompetitionIdsByCompetitionType(3);
        }
        return cachedSecondLeagueCompIds;
    }

    @PostConstruct
    public void initializeRound() {
        this.round = gameInitializationService.initializeRound();
    }

    /**
     * Leagues overview — for the leagues-overview frontend page.
     * Returns all first-division leagues sorted by their nation's UEFA-style
     * coefficient rank (rank 1 = strongest), each with:
     *  - top N standings (positional + W/D/L/GF/GA/GD/Pts)
     *  - the qualification zones for that rank (which 1-based positions go to
     *    LoC group / LoC qualifying / LoC preliminary / Stars Cup / relegation)
     * so the frontend can color each row based on what that position earns.
     */
    @GetMapping("/leaguesOverview")
    public Map<String, Object> getLeaguesOverview(@RequestParam(defaultValue = "5") int topN) {
        return competitionDisplayService.getLeaguesOverview(topN);
    }

    @GetMapping("/cupsOverview")
    public Map<String, Object> getCupsOverview() {
        return cupBracketService.getCupsOverview();
    }

    @GetMapping("/cupBracket/{cupId}/{season}")
    public Map<String, Object> getCupBracket(@PathVariable long cupId,
                                              @PathVariable int season) {
        return cupBracketService.getCupBracket(cupId, season);
    }

    @GetMapping("/getCurrentSeason")
    public String getCurrentSeason() {

        return String.valueOf(round.getSeason());
    }

    @GetMapping("/getCurrentRound")
    public String getCurrentRound() {

        return String.valueOf(round.getRound());
    }

    @GetMapping("/isTransferWindowOpen")
    public boolean isTransferWindowOpen() {
        return transferMarketService.isOpen();
    }

    public void setTransferWindowOpen(boolean open) {
        transferMarketService.setOpen(open);
    }

    @GetMapping("/isManagerFired")
    public boolean isManagerFired(HttpServletRequest request) {
        return userContext.isCurrentUserFired(request);
    }

    @GetMapping("/availableJobs")
    public List<Map<String, Object>> getAvailableJobs() {
        return jobOfferService.getAvailableJobs();
    }

    @PostMapping("/acceptJob")
    public String acceptJob(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        User currentUser = userContext.getUserOrNull(request);
        return jobOfferService.acceptJob(currentUser, body.get("teamId"));
    }

    // Phase 1 — final standings + relegation/promotion + AI transfers/loans
    // + objectives + transfer window open. Body in SeasonTransitionService.processEndOfSeason.
    // Kept as delegate so the GameAdvanceService call site stays unchanged.
    public void processEndOfSeason(int season) {
        seasonTransitionService.processEndOfSeason(season);
    }

    // Phase 2 — aging, retirement, regens, new fixtures, scorers init, cup brackets.
    // Body in SeasonTransitionService.processNewSeasonSetup.
    public void processNewSeasonSetup(int season) {
        seasonTransitionService.processNewSeasonSetup(season);
    }

    // Thin delegate to SeasonTransitionService.handleContractExpiries — kept so
    // GameAdvanceService (CONTRACT_EXPIRY_CHECK calendar event) can keep calling
    // through the controller.
    public void handleContractExpiries(int newSeason) {
        seasonTransitionService.handleContractExpiries(newSeason);
    }

    @GetMapping("/historical/getTeams/{seasonNumber}/{competitionId}")
    public List<TeamCompetitionView> getHistoricalTeamDetails(@PathVariable(name = "competitionId") long competitionId, @PathVariable(name = "seasonNumber") long seasonNumber) {
        return competitionDisplayService.getHistoricalTeamDetails(competitionId, seasonNumber);
    }

    @GetMapping("/getTeams/{competitionId}")
    public List<TeamCompetitionView> getTeamDetails(@PathVariable(name = "competitionId") long competitionId, HttpServletRequest request) {
        return competitionDisplayService.getTeamDetails(competitionId, userContext.getTeamIdOrNull(request));
    }

    @GetMapping("/getAllCompetitions")
    public List<Competition> getAllCompetitions() {

        return competitionRepository
                .findAll();
    }

    @GetMapping("/getAllCompetitions/{typeId}")
    public List<Competition> getAllCompetitionsByTypeId(@PathVariable(name = "typeId") long typeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == typeId)
                .collect(Collectors.toList());
    }

    @GetMapping("/getTeamCompetitions/{teamId}")
    public List<Map<String, Object>> getTeamCompetitions(@PathVariable(name = "teamId") long teamId) {
        return competitionDisplayService.getTeamCompetitions(teamId);
    }

    @GetMapping("/getCoefficients")
    public List<Map<String, Object>> getCoefficients() {
        return getCountryCoefficients();
    }

    @GetMapping("/getEuropeanGroups/{competitionId}/{season}")
    public List<Map<String, Object>> getEuropeanGroups(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") long season) {
        return europeanCompetitionService.getEuropeanGroups(competitionId, season);
    }

    @GetMapping("/getMatchesByCompetitionAndSeason/{competitionId}/{season}")
    public List<CompetitionTeamInfoDetail> getMatchesByCompetitionAndSeason(
            @PathVariable(name = "competitionId") long competitionId,
            @PathVariable(name = "season") long season) {

        List<CompetitionTeamInfoDetail> results = competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && d.getSeasonNumber() == season)
                .toList();

        // Debug: count matches per round
        Map<Long, Long> matchesPerRound = results.stream()
                .collect(Collectors.groupingBy(CompetitionTeamInfoDetail::getRoundId, Collectors.counting()));
        System.out.println("=== getMatchesByCompetitionAndSeason comp=" + competitionId + " season=" + season
                + " total=" + results.size() + " byRound=" + matchesPerRound);

        return results;
    }

    @GetMapping("/getCompetitionName/{competitionId}")
    public String getCompetitionName(@PathVariable(name = "competitionId") long competitionId) {
        return competitionRepository.findById(competitionId)
                .map(Competition::getName)
                .orElse("Unknown Competition");
    }

    @GetMapping("/getCupRoundCount/{competitionId}")
    public Map<String, Object> getCupRoundCount(@PathVariable(name = "competitionId") long competitionId) {
        return cupBracketService.getCupRoundCount(competitionId);
    }

    @GetMapping("getResults/{competitionId}/{roundId}")
    public List<CompetitionTeamInfoDetail> getResults(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long currentSeason = Long.parseLong(getCurrentSeason());

        List<CompetitionTeamInfoDetail> competitionTeamInfoDetails =
                competitionTeamInfoDetailRepository
                        .findAll()
                        .stream()
                        .filter(d -> d.getRoundId() == _roundId)
                        .filter(d -> d.getCompetitionId() == _competitionId)
                        .filter(d -> d.getSeasonNumber() == currentSeason)
                        .toList();

        return competitionTeamInfoDetails;

    }

    @GetMapping("getResults/{competitionId}/{roundId}/{season}")
    public List<CompetitionTeamInfoDetail> getResultsBySeason(
            @PathVariable(name = "competitionId") String competitionId,
            @PathVariable(name = "roundId") String roundId,
            @PathVariable(name = "season") long season) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        return competitionTeamInfoDetailRepository
                .findAll()
                .stream()
                .filter(d -> d.getRoundId() == _roundId)
                .filter(d -> d.getCompetitionId() == _competitionId)
                .filter(d -> d.getSeasonNumber() == season)
                .toList();
    }

    @GetMapping("getParticipants/{competitionId}/{roundId}")
    public List<Long> getParticipants(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(_roundId, _competitionId, Long.parseLong(getCurrentSeason()));

        List<Long> participants = new ArrayList<>(competitionTeamInfos
                .stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .collect(Collectors.toSet()));

        return participants;
    }

    @GetMapping("getFuturesMatches/{competitionId}/{roundId}")
    public List<TeamMatchView> getNotPlayedMatches(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {
        return competitionDisplayService.getNotPlayedMatches(Long.parseLong(competitionId), Long.parseLong(roundId));
    }

    @GetMapping("getFixtures/{competitionId}/{roundId}")
    public void getFixturesForRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {
        fixtureSchedulingService.getFixturesForRound(competitionId, roundId);
    }

    // simulateRound + its batched AI helpers + per-round caches were extracted
    // to MatchSimulationOrchestrator (Stage 2 of matchday-orchestration
    // extraction). The REST mapping stays here; everything else delegates.
    @GetMapping("simulateRound/{competitionId}/{roundId}")
    public void simulateRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {
        matchSimulationOrchestrator.simulateRound(competitionId, roundId);
    }


    // adjustTeamPowerByTacticalProperties + getBestElevenRatingByTactic +
    // getScorersForTeam + getAssistWeight + getManagerMoraleMultiplier extracted
    // to LineupRatingService. Orchestrator + interactive-live-match commit path
    // call the service directly — no controller delegates remain.

    @GetMapping("/getAllCompetitionTypes")
    public List<CompetitionType> getAllCompetitionTypes() {

        List<CompetitionType> competitionTypes = new ArrayList<>();

        CompetitionType championship = new CompetitionType();
        championship.setId(1);
        championship.setTypeName("Championship");
        championship.setTypeId(1);
        competitionTypes.add(championship);

        CompetitionType cup = new CompetitionType();
        cup.setId(2);
        cup.setTypeName("Cup");
        cup.setTypeId(2);
        competitionTypes.add(cup);

        return competitionTypes;
    }

    public HashMap<String, Integer> getMinimumPositionNeeded() {

        HashMap<String, Integer> minimumPositionNeeded = new HashMap<>();
        minimumPositionNeeded.put("GK", 1);
        minimumPositionNeeded.put("DL", 1);
        minimumPositionNeeded.put("DC", 2);
        minimumPositionNeeded.put("DR", 1);
        minimumPositionNeeded.put("MC", 2);
        minimumPositionNeeded.put("ML", 1);
        minimumPositionNeeded.put("MR", 1);
        minimumPositionNeeded.put("ST", 2);

        return minimumPositionNeeded;
    }

    public HashMap<String, Integer> getMaximumPositionAllowed() {

        HashMap<String, Integer> maximumPositionAllowed = new HashMap<>();
        maximumPositionAllowed.put("GK", 3);
        maximumPositionAllowed.put("DL", 3);
        maximumPositionAllowed.put("DC", 5);
        maximumPositionAllowed.put("DR", 3);
        maximumPositionAllowed.put("MC", 5);
        maximumPositionAllowed.put("ML", 3);
        maximumPositionAllowed.put("MR", 3);
        maximumPositionAllowed.put("ST", 5);

        return maximumPositionAllowed;
    }


    /** Delegates to {@link TransferValueCalculator#calculate} — preserved as
     *  an instance method so existing callers (AdminController, …) need no
     *  change. New code should call the calculator directly. */
    public long calculateTransferValue(long age, String position, double rating) {
        return TransferValueCalculator.calculate(age, position, rating);
    }

    @GetMapping("/getCompetitionInfo/{id}")
    public Map<String, Object> getCompetitionInfo(@PathVariable Long id) {
        return competitionDisplayService.getCompetitionInfo(id);
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getTeamNameByTeamId(@PathVariable(name = "competitionId") long competitionId) {

        return competitionRepository.findNameById(competitionId);
    }

    // Thin delegate — body in CompetitionDisplayService.getTeamCountForCompetition
    // (called from MatchSimulationOrchestrator via controllerRef + from getCupRoundCount below).
    public int getTeamCountForCompetition(long competitionId) {
        return competitionDisplayService.getTeamCountForCompetition(competitionId);
    }


    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        return europeanCompetitionService.getEuropeanSummary();
    }

    @GetMapping("/getCountryCoefficients")
    public List<Map<String, Object>> getCountryCoefficients() {
        return europeanCompetitionService.getCountryCoefficients();
    }

    @GetMapping("/getClubCoefficients")
    public List<Map<String, Object>> getClubCoefficients() {
        return europeanCompetitionService.getClubCoefficients();
    }



    // European competition delegates removed — internal callers
    // (simulateMatchday dispatch + getFixturesForRound knockout draws)
    // call europeanCompetitionService.X() directly.

    public Set<Long> getCompetitionIdsByCompetitionType(int competitionTypeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == competitionTypeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    // Thin delegate — body in MatchSimulationOrchestrator.simulateMatchday (called by GameAdvanceService).
    public void simulateMatchday(long competitionId, int matchday, int season) {
        matchSimulationOrchestrator.simulateMatchday(competitionId, matchday, season);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.getAllMatchResults (called by GameAdvanceService).
    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        return matchSimulationOrchestrator.getAllMatchResults(competitionId, matchday, season);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.finalizeInteractiveLiveMatch (called by MatchController.commit).
    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        return matchSimulationOrchestrator.finalizeInteractiveLiveMatch(liveKey);
    }

    // Thin delegate — body in MatchSimulationOrchestrator.getHumanMatchResult (called by GameAdvanceService).
    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        return matchSimulationOrchestrator.getHumanMatchResult(competitionId, matchday, season, humanTeamId);
    }

}
