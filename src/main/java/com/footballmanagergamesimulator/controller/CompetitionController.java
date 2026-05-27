package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionType;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.service.CompetitionDisplayService;
import com.footballmanagergamesimulator.service.CupBracketService;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.FixtureSchedulingService;
import com.footballmanagergamesimulator.service.GameInitializationService;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CompetitionController {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository;
    private final CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;

    private final CompetitionDisplayService competitionDisplayService;
    private final CupBracketService cupBracketService;
    private final EuropeanCompetitionService europeanCompetitionService;
    private final FixtureSchedulingService fixtureSchedulingService;
    private final GameInitializationService gameInitializationService;
    private final JobOfferService jobOfferService;
    private final MatchSimulationOrchestrator matchSimulationOrchestrator;
    private final TransferMarketService transferMarketService;
    private final UserContext userContext;

    Round round;

    private Set<Long> cachedLeagueCompIds = null;
    private Set<Long> cachedCupCompIds = null;
    private Set<Long> cachedSecondLeagueCompIds = null;

    public CompetitionController(CompetitionRepository competitionRepository,
                                 CompetitionTeamInfoRepository competitionTeamInfoRepository,
                                 CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository,
                                 CompetitionDisplayService competitionDisplayService,
                                 CupBracketService cupBracketService,
                                 EuropeanCompetitionService europeanCompetitionService,
                                 FixtureSchedulingService fixtureSchedulingService,
                                 GameInitializationService gameInitializationService,
                                 JobOfferService jobOfferService,
                                 MatchSimulationOrchestrator matchSimulationOrchestrator,
                                 TransferMarketService transferMarketService,
                                 UserContext userContext) {
        this.competitionRepository = competitionRepository;
        this.competitionTeamInfoRepository = competitionTeamInfoRepository;
        this.competitionTeamInfoDetailRepository = competitionTeamInfoDetailRepository;
        this.competitionDisplayService = competitionDisplayService;
        this.cupBracketService = cupBracketService;
        this.europeanCompetitionService = europeanCompetitionService;
        this.fixtureSchedulingService = fixtureSchedulingService;
        this.gameInitializationService = gameInitializationService;
        this.jobOfferService = jobOfferService;
        this.matchSimulationOrchestrator = matchSimulationOrchestrator;
        this.transferMarketService = transferMarketService;
        this.userContext = userContext;
    }

    @PostConstruct
    public void initializeRound() {
        this.round = gameInitializationService.initializeRound();
    }

    // ============================================================
    //  State accessors (used by services via @Lazy back-reference)
    // ============================================================

    /** Exposes the in-memory Round so services see the same value
     *  {@link #getCurrentSeason()} reads. */
    public Round getRoundCache() {
        return round;
    }

    /** Lazily populated competition-type ID caches exposed for
     *  {@link MatchSimulationOrchestrator} and {@link com.footballmanagergamesimulator.service.LineupRatingService}
     *  to share a single warm set per type. */
    public Set<Long> getLeagueCompetitionIdsCached() {
        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = competitionRepository.findIdsByTypeId(1);
        }
        return cachedLeagueCompIds;
    }

    public Set<Long> getCupCompetitionIdsCached() {
        if (cachedCupCompIds == null) {
            cachedCupCompIds = competitionRepository.findIdsByTypeId(2);
        }
        return cachedCupCompIds;
    }

    public Set<Long> getSecondLeagueCompetitionIdsCached() {
        if (cachedSecondLeagueCompIds == null) {
            cachedSecondLeagueCompIds = competitionRepository.findIdsByTypeId(3);
        }
        return cachedSecondLeagueCompIds;
    }

    // ============================================================
    //  Current state (round + transfer window + manager status)
    // ============================================================

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

    @GetMapping("/isManagerFired")
    public boolean isManagerFired(HttpServletRequest request) {
        return userContext.isCurrentUserFired(request);
    }

    // ============================================================
    //  Competition metadata
    // ============================================================

    @GetMapping("/getAllCompetitions")
    public List<Competition> getAllCompetitions() {
        return competitionRepository.findAll();
    }

    @GetMapping("/getAllCompetitions/{typeId}")
    public List<Competition> getAllCompetitionsByTypeId(@PathVariable long typeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == typeId)
                .toList();
    }

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

    @GetMapping("/getCompetitionName/{competitionId}")
    public String getCompetitionName(@PathVariable long competitionId) {
        return competitionRepository.findById(competitionId)
                .map(Competition::getName)
                .orElse("Unknown Competition");
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getTeamNameByTeamId(@PathVariable long competitionId) {
        return competitionRepository.findNameById(competitionId);
    }

    @GetMapping("/getCompetitionInfo/{id}")
    public Map<String, Object> getCompetitionInfo(@PathVariable Long id) {
        return competitionDisplayService.getCompetitionInfo(id);
    }

    // ============================================================
    //  Overviews & team-centric display
    // ============================================================

    @GetMapping("/leaguesOverview")
    public Map<String, Object> getLeaguesOverview(@RequestParam(defaultValue = "5") int topN) {
        return competitionDisplayService.getLeaguesOverview(topN);
    }

    @GetMapping("/cupsOverview")
    public Map<String, Object> getCupsOverview() {
        return cupBracketService.getCupsOverview();
    }

    @GetMapping("/cupBracket/{cupId}/{season}")
    public Map<String, Object> getCupBracket(@PathVariable long cupId, @PathVariable int season) {
        return cupBracketService.getCupBracket(cupId, season);
    }

    @GetMapping("/getCupRoundCount/{competitionId}")
    public Map<String, Object> getCupRoundCount(@PathVariable long competitionId) {
        return cupBracketService.getCupRoundCount(competitionId);
    }

    @GetMapping("/getTeams/{competitionId}")
    public List<TeamCompetitionView> getTeamDetails(@PathVariable long competitionId,
                                                    HttpServletRequest request) {
        return competitionDisplayService.getTeamDetails(competitionId, userContext.getTeamIdOrNull(request));
    }

    @GetMapping("/historical/getTeams/{seasonNumber}/{competitionId}")
    public List<TeamCompetitionView> getHistoricalTeamDetails(@PathVariable long competitionId,
                                                              @PathVariable long seasonNumber) {
        return competitionDisplayService.getHistoricalTeamDetails(competitionId, seasonNumber);
    }

    @GetMapping("/getTeamCompetitions/{teamId}")
    public List<Map<String, Object>> getTeamCompetitions(@PathVariable long teamId) {
        return competitionDisplayService.getTeamCompetitions(teamId);
    }

    // ============================================================
    //  Results & participants
    // ============================================================

    @GetMapping("getResults/{competitionId}/{roundId}")
    public List<CompetitionTeamInfoDetail> getResults(@PathVariable String competitionId,
                                                      @PathVariable String roundId) {
        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long currentSeason = round.getSeason();

        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getRoundId() == _roundId)
                .filter(d -> d.getCompetitionId() == _competitionId)
                .filter(d -> d.getSeasonNumber() == currentSeason)
                .toList();
    }

    @GetMapping("getResults/{competitionId}/{roundId}/{season}")
    public List<CompetitionTeamInfoDetail> getResultsBySeason(@PathVariable String competitionId,
                                                              @PathVariable String roundId,
                                                              @PathVariable long season) {
        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getRoundId() == _roundId)
                .filter(d -> d.getCompetitionId() == _competitionId)
                .filter(d -> d.getSeasonNumber() == season)
                .toList();
    }

    @GetMapping("/getMatchesByCompetitionAndSeason/{competitionId}/{season}")
    public List<CompetitionTeamInfoDetail> getMatchesByCompetitionAndSeason(@PathVariable long competitionId,
                                                                            @PathVariable long season) {
        return competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && d.getSeasonNumber() == season)
                .toList();
    }

    @GetMapping("getParticipants/{competitionId}/{roundId}")
    public List<Long> getParticipants(@PathVariable String competitionId,
                                      @PathVariable String roundId) {
        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfo> rows = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(_roundId, _competitionId, round.getSeason());

        return rows.stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed().distinct().collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("getFuturesMatches/{competitionId}/{roundId}")
    public List<TeamMatchView> getNotPlayedMatches(@PathVariable String competitionId,
                                                   @PathVariable String roundId) {
        return competitionDisplayService.getNotPlayedMatches(Long.parseLong(competitionId), Long.parseLong(roundId));
    }

    // ============================================================
    //  Fixture generation & round simulation
    // ============================================================

    @GetMapping("getFixtures/{competitionId}/{roundId}")
    public void getFixturesForRound(@PathVariable String competitionId,
                                    @PathVariable String roundId) {
        fixtureSchedulingService.getFixturesForRound(competitionId, roundId);
    }

    @GetMapping("simulateRound/{competitionId}/{roundId}")
    public void simulateRound(@PathVariable String competitionId,
                              @PathVariable String roundId) {
        matchSimulationOrchestrator.simulateRound(competitionId, roundId);
    }

    // ============================================================
    //  European competitions
    // ============================================================

    @GetMapping("/getEuropeanGroups/{competitionId}/{season}")
    public List<Map<String, Object>> getEuropeanGroups(@PathVariable long competitionId,
                                                       @PathVariable long season) {
        return europeanCompetitionService.getEuropeanGroups(competitionId, season);
    }

    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        return europeanCompetitionService.getEuropeanSummary();
    }

    @GetMapping("/getCoefficients")
    public List<Map<String, Object>> getCoefficients() {
        return europeanCompetitionService.getCountryCoefficients();
    }

    @GetMapping("/getCountryCoefficients")
    public List<Map<String, Object>> getCountryCoefficients() {
        return europeanCompetitionService.getCountryCoefficients();
    }

    @GetMapping("/getClubCoefficients")
    public List<Map<String, Object>> getClubCoefficients() {
        return europeanCompetitionService.getClubCoefficients();
    }

    // ============================================================
    //  Job offers
    // ============================================================

    @GetMapping("/availableJobs")
    public List<Map<String, Object>> getAvailableJobs() {
        return jobOfferService.getAvailableJobs();
    }

    @PostMapping("/acceptJob")
    public String acceptJob(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        User currentUser = userContext.getUserOrNull(request);
        return jobOfferService.acceptJob(currentUser, body.get("teamId"));
    }
}
