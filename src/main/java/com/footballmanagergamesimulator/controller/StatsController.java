package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerEntry;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.TeamDataHubStats;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.frontend.PlayerAnalyticsView;
import com.footballmanagergamesimulator.service.PlayerAnalyticsService;
import com.footballmanagergamesimulator.service.StatsAggregationService;
import com.footballmanagergamesimulator.service.StatsService;
import com.footballmanagergamesimulator.service.LeagueStrengthService;
import com.footballmanagergamesimulator.service.ScorerLeaderboardSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for player + team stats. Heavy aggregations and leaderboard
 * computations live in {@link StatsAggregationService} after the §6.6 split;
 * this controller stays thin (one URL → one delegate).
 */
@RestController
@RequestMapping("/stats")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class StatsController {

    @Autowired ScorerRepository scorerRepository;
    @Autowired StatsService statsService;
    @Autowired StatsAggregationService statsAggregationService;
    @Autowired PlayerAnalyticsService playerAnalyticsService;
    @Autowired LeagueStrengthService leagueStrengthService;
    @Autowired ScorerLeaderboardSyncService scorerLeaderboardSyncService;

    // ==================== SCORER ENTRY LOOKUPS ====================

    @GetMapping(value = "/getStats/{playerId}/{seasonNumber}")
    public Map<Integer, ScorerEntry> getStatsByPlayerForCompetitionIdAndSeasonNumber(
            @PathVariable long playerId, @PathVariable int seasonNumber) {
        List<Scorer> scorers = scorerRepository.findByPlayerIdAndSeasonNumber(playerId, seasonNumber);
        return statsService.getScorerEntriesFromScorers(scorers);
    }

    @GetMapping(value = "/getStats/{playerId}")
    public Map<Integer, ScorerEntry> getStatsByPlayerForCompetitionId(@PathVariable long playerId) {
        List<Scorer> scorers = scorerRepository.findAllByPlayerId(playerId);
        return statsService.getScorerEntriesFromScorers(scorers);
    }

    @GetMapping(value = "/getStats/all/{competitionId}")
    public List<Map<Integer, ScorerEntry>> getStatsForAllPlayersByCompetitionId(@PathVariable long competitionId) {
        return groupAndMap(scorerRepository.findAllByCompetitionId(competitionId));
    }

    /*
    Get Stats of players scoring goals... like top 100 scorers by competitionId
    You should have playerId, playerName, teamId, teamName, numberOfGames, numberOfGoals, rating
     */
    @GetMapping(value = "/getStats/all2/{competitionId}")
    public List<Map<Integer, ScorerEntry>> getStatsForPlayers(@PathVariable long competitionId) {
        return groupAndMap(scorerRepository.findAllByCompetitionId(competitionId));
    }

    @GetMapping(value = "/getStats/all3/{seasonNumber}")
    public List<Map<Integer, ScorerEntry>> getStatsForSeason(@PathVariable int seasonNumber) {
        return groupAndMap(
                scorerRepository.findAllBySeasonNumberAndRoundNumberGreaterThan(seasonNumber, 0));
    }

    /** Shared helper for the three list endpoints above. Groups scorers by player
     *  then projects each group through {@link StatsService#getScorerEntriesFromScorers}. */
    private List<Map<Integer, ScorerEntry>> groupAndMap(List<Scorer> scorers) {
        Map<Long, List<Scorer>> byPlayer = new HashMap<>();
        for (Scorer s : scorers) {
            byPlayer.computeIfAbsent(s.getPlayerId(), k -> new ArrayList<>()).add(s);
        }
        return byPlayer.values().stream()
                .map(statsService::getScorerEntriesFromScorers)
                .toList();
    }

    // ==================== LEADERBOARDS ====================

    @GetMapping("/playerStats/leaderboard")
    public Map<Long, ScorerLeaderboardEntry> getPlayerStatsForAllPlayers() {
        return scorerLeaderboardSyncService.synchronizeAllPlayers();
    }

    // ==================== AGGREGATED VIEWS (delegated to StatsAggregationService) ====================

    @GetMapping("/overview/{seasonNumber}")
    public Map<String, Object> getSeasonOverviewStats(@PathVariable int seasonNumber,
                                                       @RequestParam(defaultValue = "10") int limit,
                                                       @RequestParam(defaultValue = "LEAGUE") String scope) {
        return statsAggregationService.getSeasonOverviewStats(seasonNumber, limit, scope);
    }

    /** Compact current-season leaderboard for the Home screen of one club. */
    @GetMapping("/team/{teamId}/season/{seasonNumber}")
    public List<Map<String, Object>> getTeamSeasonPlayerStats(@PathVariable long teamId,
                                                              @PathVariable int seasonNumber,
                                                              @RequestParam(defaultValue = "3") int limit) {
        return statsAggregationService.getTeamSeasonPlayerStats(teamId, seasonNumber, limit);
    }

    /** Domestic championship quality ranking used by the global award weights. */
    @GetMapping("/league-strength/{seasonNumber}")
    public LeagueStrengthService.LeagueStrengthTable getLeagueStrength(@PathVariable int seasonNumber) {
        return leagueStrengthService.calculate(seasonNumber);
    }

    @GetMapping(value = "/teamDataHub/{teamId}/{seasonNumber}")
    public TeamDataHubStats getTeamDataHubStats(@PathVariable long teamId, @PathVariable int seasonNumber) {
        return statsAggregationService.getTeamDataHubStats(teamId, seasonNumber);
    }

    /**
     * GET /stats/championshipStats/{competitionId}/{season}
     * Aggregates match_stats across the (competition, season) and returns
     * per-category leaderboards (Most Possession, Best xG, Most Cards, etc).
     */
    @GetMapping("/championshipStats/{competitionId}/{season}")
    public Map<String, Object> getChampionshipStats(@PathVariable long competitionId, @PathVariable int season) {
        return statsAggregationService.getChampionshipStats(competitionId, season);
    }

    /**
     * GET /stats/allTimeChampions
     * Ranking of teams by number of titles won, broken down by competition type.
     * Sorted by totalTitles descending.
     */
    @GetMapping("/allTimeChampions")
    public List<Map<String, Object>> getAllTimeChampions() {
        return statsAggregationService.getAllTimeChampions();
    }

    /**
     * GET /stats/competition/{competitionId}/{seasonNumber}
     * Top scorers/assisters/rated/clean-sheets/disciplinary leaderboards for
     * a specific competition and season.
     */
    @GetMapping("/competition/{competitionId}/{seasonNumber}")
    public Map<String, Object> getCompetitionStats(@PathVariable long competitionId, @PathVariable int seasonNumber) {
        return statsAggregationService.getCompetitionStats(competitionId, seasonNumber);
    }

    /** One eligible rating leader per team, compared with that team's average. */
    @GetMapping("/competition/{competitionId}/{seasonNumber}/rating-impact")
    public Map<String, Object> getCompetitionRatingImpact(@PathVariable long competitionId,
                                                           @PathVariable int seasonNumber) {
        return statsAggregationService.getCompetitionRatingImpact(competitionId, seasonNumber);
    }

    /** Historical rating-impact report across every played competition and season. */
    @GetMapping("/rating-impact/history")
    public Map<String, Object> getRatingImpactHistory(
            @RequestParam(defaultValue = "55") int teamAppearancePercentage,
            @RequestParam(defaultValue = "60") int competitionAppearancePercentage) {
        return statsAggregationService.getRatingImpactHistory(
                teamAppearancePercentage, competitionAppearancePercentage);
    }

    /**
     * GET /stats/playerForm/{playerId}
     * Last 5 appearances + form trend (last 5 vs previous 5 avg ratings).
     */
    @GetMapping("/playerForm/{playerId}")
    public Map<String, Object> getPlayerForm(@PathVariable long playerId) {
        return statsAggregationService.getPlayerForm(playerId);
    }

    /**
     * GET /stats/compare/{playerId1}/{playerId2}
     * Head-to-head comparison of two players across all seasons and competitions.
     */
    @GetMapping("/compare/{playerId1}/{playerId2}")
    public Map<String, Object> comparePlayersHeadToHead(@PathVariable long playerId1, @PathVariable long playerId2) {
        return statsAggregationService.comparePlayersHeadToHead(playerId1, playerId2);
    }

    /**
     * GET /stats/team/{teamId}/competitionBreakdown
     * Per-(competition, season) team record — League / Cup / LoC / Stars Cup kept
     * separate instead of summed together. League position only on league lines;
     * completed seasons return their archived final position.
     */
    @GetMapping("/team/{teamId}/competitionBreakdown")
    public List<com.footballmanagergamesimulator.model.CompetitionStatLine> getTeamCompetitionBreakdown(@PathVariable long teamId) {
        return statsAggregationService.getTeamCompetitionBreakdown(teamId);
    }

    /**
     * GET /stats/player/{playerId}/competitionBreakdown
     * All-competitions total plus per-competition and per-(type, season)
     * goals/assists/appearances for one player.
     */
    @GetMapping("/player/{playerId}/competitionBreakdown")
    public Map<String, Object> getPlayerCompetitionBreakdown(@PathVariable long playerId) {
        return statsAggregationService.getPlayerCompetitionBreakdown(playerId);
    }

    /**
     * GET /stats/player/{playerId}/{competitionId}/{seasonNumber}/analytics
     * Synthetic StatsBomb-style analytics (Faza 1): per-90 "expected" metrics
     * synthesized from attributes, percentile-ranked vs same-position-group peers
     * in the (competition, season), plus an attribute-modulated pitch heatmap.
     */
    @GetMapping("/player/{playerId}/{competitionId}/{seasonNumber}/analytics")
    public PlayerAnalyticsView getPlayerAnalytics(@PathVariable long playerId,
                                                  @PathVariable long competitionId,
                                                  @PathVariable int seasonNumber) {
        return playerAnalyticsService.getPlayerAnalytics(playerId, competitionId, seasonNumber);
    }
}
