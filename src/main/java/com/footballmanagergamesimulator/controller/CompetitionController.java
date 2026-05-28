package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionType;
import com.footballmanagergamesimulator.service.CompetitionDisplayService;
import com.footballmanagergamesimulator.service.CompetitionQueryService;
import com.footballmanagergamesimulator.service.CupBracketService;
import com.footballmanagergamesimulator.service.EuropeanDisplayService;
import com.footballmanagergamesimulator.service.FixtureSchedulingService;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.TransferMarketService;
import com.footballmanagergamesimulator.user.UserContext;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CompetitionController {

    private final CompetitionDisplayService competitionDisplayService;
    private final CompetitionQueryService competitionQueryService;
    private final CupBracketService cupBracketService;
    private final EuropeanDisplayService europeanDisplayService;
    private final FixtureSchedulingService fixtureSchedulingService;
    private final GameStateService gameStateService;
    private final MatchSimulationOrchestrator matchSimulationOrchestrator;
    private final TransferMarketService transferMarketService;
    private final UserContext userContext;

    public CompetitionController(CompetitionDisplayService competitionDisplayService,
                                 CompetitionQueryService competitionQueryService,
                                 CupBracketService cupBracketService,
                                 EuropeanDisplayService europeanDisplayService,
                                 FixtureSchedulingService fixtureSchedulingService,
                                 GameStateService gameStateService,
                                 MatchSimulationOrchestrator matchSimulationOrchestrator,
                                 TransferMarketService transferMarketService,
                                 UserContext userContext) {
        this.competitionDisplayService = competitionDisplayService;
        this.competitionQueryService = competitionQueryService;
        this.cupBracketService = cupBracketService;
        this.europeanDisplayService = europeanDisplayService;
        this.fixtureSchedulingService = fixtureSchedulingService;
        this.gameStateService = gameStateService;
        this.matchSimulationOrchestrator = matchSimulationOrchestrator;
        this.transferMarketService = transferMarketService;
        this.userContext = userContext;
    }

    // ============================================================
    //  Current state (round + transfer window + manager status)
    // ============================================================

    @GetMapping("/getCurrentSeason")
    public String getCurrentSeason() {
        return String.valueOf(gameStateService.currentSeason());
    }

    @GetMapping("/getCurrentRound")
    public String getCurrentRound() {
        return String.valueOf(gameStateService.currentRound());
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
        return competitionQueryService.getAllCompetitions();
    }

    @GetMapping("/getAllCompetitions/{typeId}")
    public List<Competition> getAllCompetitionsByTypeId(@PathVariable long typeId) {
        return competitionQueryService.getAllCompetitionsByTypeId(typeId);
    }

    @GetMapping("/getAllCompetitionTypes")
    public List<CompetitionType> getAllCompetitionTypes() {
        return competitionQueryService.getAllCompetitionTypes();
    }

    @GetMapping("/getCompetitionName/{competitionId}")
    public String getCompetitionName(@PathVariable long competitionId) {
        return competitionQueryService.getCompetitionName(competitionId);
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getCompetitionNameById(@PathVariable long competitionId) {
        return competitionQueryService.getCompetitionNameById(competitionId);
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
        return competitionQueryService.getResults(Long.parseLong(competitionId), Long.parseLong(roundId));
    }

    @GetMapping("getResults/{competitionId}/{roundId}/{season}")
    public List<CompetitionTeamInfoDetail> getResultsBySeason(@PathVariable String competitionId,
                                                               @PathVariable String roundId,
                                                               @PathVariable long season) {
        return competitionQueryService.getResultsBySeason(Long.parseLong(competitionId), Long.parseLong(roundId), season);
    }

    @GetMapping("/getMatchesByCompetitionAndSeason/{competitionId}/{season}")
    public List<CompetitionTeamInfoDetail> getMatchesByCompetitionAndSeason(@PathVariable long competitionId,
                                                                             @PathVariable long season) {
        return competitionQueryService.getMatchesByCompetitionAndSeason(competitionId, season);
    }

    @GetMapping("getParticipants/{competitionId}/{roundId}")
    public List<Long> getParticipants(@PathVariable String competitionId,
                                       @PathVariable String roundId) {
        return competitionQueryService.getParticipants(Long.parseLong(competitionId), Long.parseLong(roundId));
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
        return europeanDisplayService.getEuropeanGroups(competitionId, season);
    }

    @GetMapping("/getEuropeanSummary")
    public Map<String, Object> getEuropeanSummary() {
        return europeanDisplayService.getEuropeanSummary();
    }

    @GetMapping("/getCoefficients")
    public List<Map<String, Object>> getCoefficients() {
        return europeanDisplayService.getCountryCoefficients();
    }

    @GetMapping("/getCountryCoefficients")
    public List<Map<String, Object>> getCountryCoefficients() {
        return europeanDisplayService.getCountryCoefficients();
    }

    @GetMapping("/getClubCoefficients")
    public List<Map<String, Object>> getClubCoefficients() {
        return europeanDisplayService.getClubCoefficients();
    }
}
