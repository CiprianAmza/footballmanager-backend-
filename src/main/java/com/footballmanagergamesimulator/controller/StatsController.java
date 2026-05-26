package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.*;

@RestController
@RequestMapping("/stats")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class StatsController {

    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    StatsService statsService;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    MatchEventRepository matchEventRepository;
    @Autowired
    MatchStatsRepository matchStatsRepository;
    @Autowired
    TransferRepository transferRepository;

    @GetMapping(value = "/getStats/{playerId}/{seasonNumber}")
    public Map<Integer, ScorerEntry> getStatsByPlayerForCompetitionIdAndSeasonNumber(@PathVariable(name = "playerId") long playerId,
                                                                                     @PathVariable(name = "seasonNumber") int seasonNumber) {

        List<Scorer> scorers = scorerRepository.findByPlayerIdAndSeasonNumber(playerId, seasonNumber);

        return statsService.getScorerEntriesFromScorers(scorers);
    }

    @GetMapping(value = "/getStats/{playerId}")
    public Map<Integer, ScorerEntry> getStatsByPlayerForCompetitionId(@PathVariable(name = "playerId") long playerId) {

        List<Scorer> scorers = scorerRepository.findAllByPlayerId(playerId);

        return statsService.getScorerEntriesFromScorers(scorers);
    }

    @GetMapping(value = "/getStats/all/{competitionId}")
    public List<Map<Integer, ScorerEntry>> getStatsForAllPlayersByCompetitionId(@PathVariable(name = "competitionId") long competitionId) {

        List<Scorer> scorers = scorerRepository.findAllByCompetitionId(competitionId);

        Map<Long, List<Scorer>> playerIdToScorerStats = new HashMap<>();
        for (Scorer scorer: scorers) {
            List<Scorer> scorerList = playerIdToScorerStats.getOrDefault(scorer.getPlayerId(), new ArrayList<>());
            scorerList.add(scorer);
            playerIdToScorerStats.put(scorer.getPlayerId(), scorerList);
        }

        return playerIdToScorerStats
                .values()
                .stream()
                .map(statsService::getScorerEntriesFromScorers)
                .toList();
    }

    /*
    Get Stats of players scoring goals... like top 100 scorers by competitionId
    You should have playerId, playerName, teamId, teamName, numberOfGames, numberOfGoals, rating
     */
    @GetMapping(value = "/getStats/all2/{competitionId}")
    public List<Map<Integer, ScorerEntry>> getStatsForPlayers(@PathVariable(name = "competitionId") long competitionId) {

        List<Scorer> scorers = scorerRepository.findAllByCompetitionId(competitionId);

        Map<Long, List<Scorer>> playerIdToScorerStats = new HashMap<>();

        for (Scorer scorer: scorers) {

            List<Scorer> scorerList = playerIdToScorerStats.getOrDefault(scorer.getPlayerId(), new ArrayList<>());
            scorerList.add(scorer);

            playerIdToScorerStats.put(scorer.getPlayerId(), scorerList);
        }

        return playerIdToScorerStats
                .values()
                .stream()
                .map(statsService::getScorerEntriesFromScorers)
                .toList();

    }

    @GetMapping(value = "/getStats/all3/{seasonNumber}")
    public List<Map<Integer, ScorerEntry>> getStatsForSeason(@PathVariable(name = "seasonNumber") int seasonNumber) {

        List<Scorer> scorers = scorerRepository.findAllBySeasonNumber(seasonNumber);

        Map<Long, List<Scorer>> playerIdToScorerStats = new HashMap<>();

        for (Scorer scorer: scorers) {

            List<Scorer> scorerList = playerIdToScorerStats.getOrDefault(scorer.getPlayerId(), new ArrayList<>());
            scorerList.add(scorer);

            playerIdToScorerStats.put(scorer.getPlayerId(), scorerList);
        }

        return playerIdToScorerStats
                .values()
                .stream()
                .map(statsService::getScorerEntriesFromScorers)
                .toList();

    }

    @GetMapping("/playerStats/leaderboard")
    public Map<Long, ScorerLeaderboardEntry> getPlayerStatsForAllPlayers() {

        List<Human> allPlayers = humanRepository.findAll();
        Map<Long, ScorerLeaderboardEntry> playerToScorerLeaderboard = new HashMap<>();

        for (Human player: allPlayers) {
            Optional<ScorerLeaderboardEntry> scorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(player.getId());

            if (scorerLeaderboardEntry.isPresent()) {
                ScorerLeaderboardEntry entry = scorerLeaderboardEntry.get();
                entry.setActive(!player.isRetired());
                playerToScorerLeaderboard.put(player.getId(), entry);

            }
        }

        return playerToScorerLeaderboard;
    }

    @GetMapping(value = "/teamDataHub/{teamId}/{seasonNumber}")
    public TeamDataHubStats getTeamDataHubStats(@PathVariable(name = "teamId") long teamId,
                                                 @PathVariable(name = "seasonNumber") int seasonNumber) {

        TeamDataHubStats stats = new TeamDataHubStats();
        stats.setTeamId(teamId);
        stats.setTeamName(teamRepository.findNameById(teamId));
        stats.setSeasonNumber(seasonNumber);

        // Get all scorer entries for this team+season (one per player per match)
        List<Scorer> teamScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber);

        if (teamScorers.isEmpty()) {
            return stats;
        }

        // Identify unique matches by tracking transitions between match blocks.
        // Scorers for the same match share the same opponent, competition, and scores.
        // When these change, we know a new match started.
        Set<String> processedMatches = new LinkedHashSet<>();
        Map<String, Integer> matchTeamScores = new LinkedHashMap<>();
        Map<String, Integer> matchOpponentScores = new LinkedHashMap<>();

        // Track unique matches by building keys from sequential scorer data
        // Since scorers are inserted per match, entries for same match share teamScore/opponentScore/opponent
        String lastMatchKey = "";
        int matchCounter = 0;
        for (Scorer s : teamScorers) {
            if (s.getTeamScore() < 0) continue;
            String candidateKey = s.getOpponentTeamId() + "_" + s.getCompetitionId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastMatchKey)) {
                matchCounter++;
                lastMatchKey = candidateKey;
            }
            String matchKey = candidateKey + "_" + matchCounter;
            processedMatches.add(matchKey);
            matchTeamScores.put(matchKey, s.getTeamScore());
            matchOpponentScores.put(matchKey, s.getOpponentScore());
        }

        int totalMatches = processedMatches.size();
        int wins = 0, draws = 0, losses = 0, goalsScored = 0, goalsConceded = 0, cleanSheets = 0;

        List<String> formList = new ArrayList<>();
        for (String mk : processedMatches) {
            int ts = matchTeamScores.get(mk);
            int os = matchOpponentScores.get(mk);
            goalsScored += ts;
            goalsConceded += os;
            if (os == 0) cleanSheets++;
            if (ts > os) { wins++; formList.add("W"); }
            else if (ts == os) { draws++; formList.add("D"); }
            else { losses++; formList.add("L"); }
        }

        stats.setTotalMatches(totalMatches);
        stats.setWins(wins);
        stats.setDraws(draws);
        stats.setLosses(losses);
        stats.setGoalsScored(goalsScored);
        stats.setGoalsConceded(goalsConceded);
        stats.setGoalsPerGame(totalMatches > 0 ? Math.round((double) goalsScored / totalMatches * 100.0) / 100.0 : 0);
        stats.setConcededPerGame(totalMatches > 0 ? Math.round((double) goalsConceded / totalMatches * 100.0) / 100.0 : 0);
        stats.setCleanSheets(cleanSheets);
        stats.setCleanSheetPercentage(totalMatches > 0 ? Math.round((double) cleanSheets / totalMatches * 100.0) / 100.0 : 0);
        stats.setWinPercentage(totalMatches > 0 ? Math.round((double) wins / totalMatches * 100.0) / 100.0 : 0);

        // Recent form (last 5)
        int formSize = formList.size();
        stats.setRecentForm(formList.subList(Math.max(0, formSize - 5), formSize));

        // Player-level aggregations
        Map<Long, Integer> playerGoals = new HashMap<>();
        Map<Long, Integer> playerAssists = new HashMap<>();
        Map<Long, Double> playerRatingSum = new HashMap<>();
        Map<Long, Integer> playerMatchCount = new HashMap<>();
        double totalRatingSum = 0;
        int totalRatingCount = 0;
        int totalAssists = 0;

        for (Scorer s : teamScorers) {
            if (s.getTeamScore() < 0) continue;
            playerGoals.merge(s.getPlayerId(), s.getGoals(), Integer::sum);
            playerAssists.merge(s.getPlayerId(), s.getAssists(), Integer::sum);
            playerRatingSum.merge(s.getPlayerId(), s.getRating(), Double::sum);
            playerMatchCount.merge(s.getPlayerId(), 1, Integer::sum);
            totalRatingSum += s.getRating();
            totalRatingCount++;
            totalAssists += s.getAssists();
        }

        stats.setAvgTeamRating(totalRatingCount > 0 ? Math.round(totalRatingSum / totalRatingCount * 100.0) / 100.0 : 0);
        stats.setTotalAssists(totalAssists);
        stats.setAssistsPerGame(totalMatches > 0 ? Math.round((double) totalAssists / totalMatches * 100.0) / 100.0 : 0);

        // Top scorer
        long topScorerId = 0;
        int topGoals = 0;
        for (Map.Entry<Long, Integer> e : playerGoals.entrySet()) {
            if (e.getValue() > topGoals) { topGoals = e.getValue(); topScorerId = e.getKey(); }
        }
        if (topScorerId > 0) {
            stats.setTopScorer(humanRepository.findById(topScorerId).map(Human::getName).orElse("Unknown"));
            stats.setTopScorerGoals(topGoals);
        }

        // Top assister
        long topAssisterId = 0;
        int topAssists = 0;
        for (Map.Entry<Long, Integer> e : playerAssists.entrySet()) {
            if (e.getValue() > topAssists) { topAssists = e.getValue(); topAssisterId = e.getKey(); }
        }
        if (topAssisterId > 0) {
            stats.setTopAssister(humanRepository.findById(topAssisterId).map(Human::getName).orElse("Unknown"));
            stats.setTopAssisterAssists(topAssists);
        }

        // Highest rated player
        long highestRatedId = 0;
        double highestAvgRating = 0;
        for (Map.Entry<Long, Double> e : playerRatingSum.entrySet()) {
            int count = playerMatchCount.getOrDefault(e.getKey(), 1);
            double avg = e.getValue() / count;
            if (avg > highestAvgRating) { highestAvgRating = avg; highestRatedId = e.getKey(); }
        }
        if (highestRatedId > 0) {
            stats.setHighestRatedPlayer(humanRepository.findById(highestRatedId).map(Human::getName).orElse("Unknown"));
            stats.setHighestRating(Math.round(highestAvgRating * 100.0) / 100.0);
        }

        // League averages: compute from all teams in the same season
        List<Scorer> allSeasonScorers = scorerRepository.findAllBySeasonNumber(seasonNumber);
        double leagueTotalRating = 0;
        int leagueTotalRatingCount = 0;
        int leagueTotalAssists = 0;

        String lastLeagueKey = "";
        int leagueMatchCounter = 0;
        Map<Long, Map<String, int[]>> teamMatchData = new HashMap<>();

        for (Scorer s : allSeasonScorers) {
            if (s.getTeamScore() < 0) continue;
            long tid = s.getTeamId();

            String candidateKey = tid + "_" + s.getOpponentTeamId() + "_" + s.getCompetitionId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastLeagueKey)) {
                leagueMatchCounter++;
                lastLeagueKey = candidateKey;
            }
            String matchKey = candidateKey + "_" + leagueMatchCounter;

            teamMatchData.computeIfAbsent(tid, k -> new LinkedHashMap<>());
            teamMatchData.get(tid).putIfAbsent(matchKey, new int[]{s.getTeamScore(), s.getOpponentScore()});

            leagueTotalRating += s.getRating();
            leagueTotalRatingCount++;
            leagueTotalAssists += s.getAssists();
        }

        int leagueTeamCount = teamMatchData.size();
        double totalLeagueGPG = 0, totalLeagueCPG = 0, totalLeagueCSPct = 0, totalLeagueWinPct = 0;
        int totalLeagueMatches = 0;

        for (Map.Entry<Long, Map<String, int[]>> teamEntry : teamMatchData.entrySet()) {
            Map<String, int[]> matches = teamEntry.getValue();
            int tMatches = matches.size();
            int tGoals = 0, tConceded = 0, tClean = 0, tWins = 0;
            for (int[] scores : matches.values()) {
                tGoals += scores[0];
                tConceded += scores[1];
                if (scores[1] == 0) tClean++;
                if (scores[0] > scores[1]) tWins++;
            }
            if (tMatches > 0) {
                totalLeagueGPG += (double) tGoals / tMatches;
                totalLeagueCPG += (double) tConceded / tMatches;
                totalLeagueCSPct += (double) tClean / tMatches;
                totalLeagueWinPct += (double) tWins / tMatches;
                totalLeagueMatches += tMatches;
            }
        }

        if (leagueTeamCount > 0) {
            stats.setLeagueAvgGoalsPerGame(Math.round(totalLeagueGPG / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgConcededPerGame(Math.round(totalLeagueCPG / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgCleanSheetPct(Math.round(totalLeagueCSPct / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgWinPct(Math.round(totalLeagueWinPct / leagueTeamCount * 100.0) / 100.0);
        }
        if (leagueTotalRatingCount > 0) {
            stats.setLeagueAvgRating(Math.round(leagueTotalRating / leagueTotalRatingCount * 100.0) / 100.0);
        }
        if (totalLeagueMatches > 0) {
            stats.setLeagueAvgAssistsPerGame(Math.round((double) leagueTotalAssists / totalLeagueMatches * 100.0) / 100.0);
        }

        return stats;
    }

    // ==================== CHAMPIONSHIP STATS (aggregated from match_stats) ====================

    /**
     * Aggregates the match_stats table across every match a team played in this
     * (competition, season) and returns per-category leaderboards. One round-trip
     * gives the frontend everything it needs to render an expandable "championship
     * stats" page (Most Possession, Most Cards, Best xG, Most Shots, etc).
     */
    @GetMapping("/championshipStats/{competitionId}/{season}")
    public Map<String, Object> getChampionshipStats(@PathVariable long competitionId,
                                                     @PathVariable int season) {
        List<MatchStats> matches = matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season);

        // Aggregate per team
        Map<Long, TeamStatAccumulator> agg = new LinkedHashMap<>();
        for (MatchStats m : matches) {
            agg.computeIfAbsent(m.getTeam1Id(), TeamStatAccumulator::new).addHomeSide(m);
            agg.computeIfAbsent(m.getTeam2Id(), TeamStatAccumulator::new).addAwaySide(m);
        }

        // Ensure every team currently in this league appears in the leaderboards,
        // even if it hasn't played a tracked match yet (financial leaderboards
        // are meaningful at season start before any match-stats exist).
        List<Team> teamsInComp = teamRepository.findAllByCompetitionId(competitionId);
        for (Team t : teamsInComp) {
            agg.computeIfAbsent(t.getId(), TeamStatAccumulator::new);
        }

        // Resolve names + colors once
        Map<Long, Team> teamMap = teamRepository.findAllById(agg.keySet()).stream()
                .collect(Collectors.toMap(Team::getId, t -> t));

        // ===== Financials (per team, this season) =====
        // Group all transfers of the season by buy/sell team so we can total
        // money spent and earned without a per-team query.
        List<Transfer> seasonTransfers = transferRepository.findAllBySeasonNumber((long) season);
        Map<Long, Long> spentByTeam = new HashMap<>();
        Map<Long, Long> earnedByTeam = new HashMap<>();
        for (Transfer t : seasonTransfers) {
            spentByTeam.merge(t.getBuyTeamId(),  t.getPlayerTransferValue(), Long::sum);
            earnedByTeam.merge(t.getSellTeamId(), t.getPlayerTransferValue(), Long::sum);
        }
        // Pre-load all human/staff wages grouped by teamId in one query
        Map<Long, Long> monthlyWagesByTeam = humanRepository.findAll().stream()
                .filter(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() > 0)
                .collect(Collectors.groupingBy(Human::getTeamId, Collectors.summingLong(Human::getWage)));

        // Build per-team stat records (averages + totals) ready for sorting
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (TeamStatAccumulator a : agg.values()) {
            Team t = teamMap.get(a.teamId);
            int mc = Math.max(a.matchCount, 1); // protect against /0 — averages will be 0 if no matches
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", a.teamId);
            row.put("teamName", t != null ? t.getName() : "?");
            row.put("teamColor1", t != null ? t.getColor1() : null);
            row.put("matches", a.matchCount);
            row.put("avgPossession",   round(a.possession   / mc, 1));
            row.put("avgPassAccuracy", round(a.passAccuracy / mc, 1));
            row.put("avgShots",        round(a.shots        / (double) mc, 1));
            row.put("avgShotsOnTarget",round(a.shotsOnTarget/ (double) mc, 1));
            row.put("avgCorners",      round(a.corners      / (double) mc, 1));
            row.put("avgFouls",        round(a.fouls        / (double) mc, 1));
            row.put("avgOffsides",     round(a.offsides     / (double) mc, 1));
            row.put("avgTackles",      round(a.tackles      / (double) mc, 1));
            row.put("avgInterceptions",round(a.interceptions/ (double) mc, 1));
            row.put("avgClearances",   round(a.clearances   / (double) mc, 1));
            row.put("avgDuelsWon",     round(a.duelsWon     / (double) mc, 1));
            row.put("avgAerialDuels",  round(a.aerialDuels  / (double) mc, 1));
            row.put("avgBigChances",   round(a.bigChances   / (double) mc, 1));
            row.put("avgXg",           round(a.xg           / 100.0 / mc, 2));
            row.put("totalYellowCards",a.yellow);
            row.put("totalRedCards",   a.red);
            row.put("totalGoals",      a.goals);
            row.put("totalConceded",   a.conceded);

            // Financials (snapshot of current state + transfer activity this season)
            long monthly = monthlyWagesByTeam.getOrDefault(a.teamId, 0L);
            long spent   = spentByTeam.getOrDefault(a.teamId, 0L);
            long earned  = earnedByTeam.getOrDefault(a.teamId, 0L);
            row.put("transferBudget",  t != null ? t.getTransferBudget() : 0L);
            row.put("totalFinances",   t != null ? t.getTotalFinances()  : 0L);
            row.put("monthlyWages",    monthly);
            row.put("annualWages",     monthly * 12);
            row.put("transferSpent",   spent);
            row.put("transferEarned",  earned);
            row.put("transferNet",     spent - earned); // + = bought more than sold
            row.put("transferProfit",  earned - spent); // + = sold more than bought

            teamRows.add(row);
        }

        // For each category, produce a sorted leaderboard of {teamId, value, displayValue}
        Map<String, Object> categories = new LinkedHashMap<>();
        categories.put("possession",      leaderboard(teamRows, "avgPossession",   "% avg",  true));
        categories.put("passAccuracy",    leaderboard(teamRows, "avgPassAccuracy", "% avg",  true));
        categories.put("shots",           leaderboard(teamRows, "avgShots",        "per match", true));
        categories.put("shotsOnTarget",   leaderboard(teamRows, "avgShotsOnTarget","per match", true));
        categories.put("corners",         leaderboard(teamRows, "avgCorners",      "per match", true));
        categories.put("bigChances",      leaderboard(teamRows, "avgBigChances",   "per match", true));
        categories.put("xg",              leaderboard(teamRows, "avgXg",           "per match", true));
        categories.put("goalsScored",     leaderboard(teamRows, "totalGoals",      "total",   true));
        categories.put("goalsConceded",   leaderboard(teamRows, "totalConceded",   "total",   false)); // asc = best defense
        categories.put("yellowCards",     leaderboard(teamRows, "totalYellowCards","total",   true));
        categories.put("redCards",        leaderboard(teamRows, "totalRedCards",   "total",   true));
        categories.put("fouls",           leaderboard(teamRows, "avgFouls",        "per match", true));
        categories.put("offsides",        leaderboard(teamRows, "avgOffsides",     "per match", true));
        categories.put("tackles",         leaderboard(teamRows, "avgTackles",      "per match", true));
        categories.put("interceptions",   leaderboard(teamRows, "avgInterceptions","per match", true));
        categories.put("clearances",      leaderboard(teamRows, "avgClearances",   "per match", true));
        categories.put("duelsWon",        leaderboard(teamRows, "avgDuelsWon",     "per match", true));
        categories.put("aerialDuels",     leaderboard(teamRows, "avgAerialDuels",  "per match", true));

        // === Financial leaderboards ===
        categories.put("transferBudget",  leaderboard(teamRows, "transferBudget",  "€ available",  true));
        categories.put("totalFinances",   leaderboard(teamRows, "totalFinances",   "€ in bank",    true));
        categories.put("monthlyWages",    leaderboard(teamRows, "monthlyWages",    "€/month",      true));
        categories.put("annualWages",     leaderboard(teamRows, "annualWages",     "€/year",       true));
        categories.put("transferSpent",   leaderboard(teamRows, "transferSpent",   "€ this season",true));
        categories.put("transferEarned",  leaderboard(teamRows, "transferEarned",  "€ this season",true));
        categories.put("transferProfit",  leaderboard(teamRows, "transferProfit",  "€ net profit", true));

        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("competitionId", competitionId);
        result.put("competitionName", comp != null ? comp.getName() : "");
        result.put("season", season);
        result.put("matchesPlayed", matches.size());
        result.put("teamsTracked", teamRows.size());
        result.put("teams", teamRows);          // full per-team table
        result.put("categories", categories);   // sorted leaderboards per metric
        return result;
    }

    /**
     * Sorts the team rows by the given numeric field and returns a small object
     * per row containing teamId / name / color / value formatted for display.
     */
    private List<Map<String, Object>> leaderboard(List<Map<String, Object>> rows, String field,
                                                   String unit, boolean descending) {
        return rows.stream()
                .sorted((a, b) -> {
                    double va = ((Number) a.getOrDefault(field, 0)).doubleValue();
                    double vb = ((Number) b.getOrDefault(field, 0)).doubleValue();
                    return descending ? Double.compare(vb, va) : Double.compare(va, vb);
                })
                .map(r -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("teamId", r.get("teamId"));
                    e.put("teamName", r.get("teamName"));
                    e.put("teamColor1", r.get("teamColor1"));
                    e.put("value", r.get(field));
                    e.put("unit", unit);
                    return e;
                })
                .toList();
    }

    private double round(double v, int decimals) {
        double mul = Math.pow(10, decimals);
        return Math.round(v * mul) / mul;
    }

    /**
     * Internal accumulator: sums up everything one team contributed across all
     * their matches in this (competition, season). All averages are computed
     * on the way out.
     */
    private static class TeamStatAccumulator {
        final long teamId;
        int matchCount = 0;
        double possession = 0, passAccuracy = 0;
        int shots = 0, shotsOnTarget = 0, corners = 0, fouls = 0, offsides = 0;
        int tackles = 0, interceptions = 0, clearances = 0;
        int duelsWon = 0, aerialDuels = 0, bigChances = 0;
        int yellow = 0, red = 0, goals = 0, conceded = 0;
        long xg = 0; // stored * 100 in DB

        TeamStatAccumulator(long teamId) { this.teamId = teamId; }

        void addHomeSide(MatchStats m) {
            matchCount++;
            possession    += m.getHomePossession();
            passAccuracy  += m.getHomePassAccuracy();
            shots         += m.getHomeShots();
            shotsOnTarget += m.getHomeShotsOnTarget();
            corners       += m.getHomeCorners();
            fouls         += m.getHomeFouls();
            offsides      += m.getHomeOffsides();
            tackles       += m.getHomeTackles();
            interceptions += m.getHomeInterceptions();
            clearances    += m.getHomeClearances();
            duelsWon      += m.getHomeDuelsWon();
            aerialDuels   += m.getHomeAerialDuelsWon();
            bigChances    += m.getHomeBigChances();
            yellow        += m.getHomeYellowCards();
            red           += m.getHomeRedCards();
            goals         += m.getHomeGoals();
            conceded      += m.getAwayGoals();
            xg            += m.getHomeXg();
        }

        void addAwaySide(MatchStats m) {
            matchCount++;
            possession    += m.getAwayPossession();
            passAccuracy  += m.getAwayPassAccuracy();
            shots         += m.getAwayShots();
            shotsOnTarget += m.getAwayShotsOnTarget();
            corners       += m.getAwayCorners();
            fouls         += m.getAwayFouls();
            offsides      += m.getAwayOffsides();
            tackles       += m.getAwayTackles();
            interceptions += m.getAwayInterceptions();
            clearances    += m.getAwayClearances();
            duelsWon      += m.getAwayDuelsWon();
            aerialDuels   += m.getAwayAerialDuelsWon();
            bigChances    += m.getAwayBigChances();
            yellow        += m.getAwayYellowCards();
            red           += m.getAwayRedCards();
            goals         += m.getAwayGoals();
            conceded      += m.getHomeGoals();
            xg            += m.getAwayXg();
        }
    }

    // ==================== 1. ALL-TIME CHAMPIONS ====================

    /**
     * GET /stats/allTimeChampions
     * Returns a ranking of teams by number of titles won, broken down by competition type.
     * Response: list of { teamId, teamName, totalTitles, leagueTitles, cupTitles,
     *                      secondLeagueTitles, locTitles, starsCupTitles, titles: [{season, competitionName, competitionTypeId}] }
     * Sorted by totalTitles descending.
     */
    @GetMapping("/allTimeChampions")
    public List<Map<String, Object>> getAllTimeChampions() {
        List<CompetitionHistory> allHistory = competitionHistoryRepository.findAll();

        // Group winners (position 1) by teamId
        Map<Long, List<CompetitionHistory>> teamWins = allHistory.stream()
                .filter(h -> h.getLastPosition() == 1)
                .collect(Collectors.groupingBy(CompetitionHistory::getTeamId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<CompetitionHistory>> entry : teamWins.entrySet()) {
            long teamId = entry.getKey();
            List<CompetitionHistory> wins = entry.getValue();

            Map<String, Object> teamData = new LinkedHashMap<>();
            teamData.put("teamId", teamId);
            teamData.put("teamName", teamRepository.findNameById(teamId));

            int leagueTitles = 0, cupTitles = 0, secondLeagueTitles = 0, locTitles = 0, starsCupTitles = 0;
            List<Map<String, Object>> titleList = new ArrayList<>();

            for (CompetitionHistory h : wins) {
                Map<String, Object> title = new LinkedHashMap<>();
                title.put("season", h.getSeasonNumber());
                title.put("competitionName", h.getCompetitionName());
                title.put("competitionTypeId", h.getCompetitionTypeId());
                titleList.add(title);

                switch ((int) h.getCompetitionTypeId()) {
                    case 1: leagueTitles++; break;
                    case 2: cupTitles++; break;
                    case 3: secondLeagueTitles++; break;
                    case 4: locTitles++; break;
                    case 5: starsCupTitles++; break;
                }
            }

            teamData.put("totalTitles", wins.size());
            teamData.put("leagueTitles", leagueTitles);
            teamData.put("cupTitles", cupTitles);
            teamData.put("secondLeagueTitles", secondLeagueTitles);
            teamData.put("locTitles", locTitles);
            teamData.put("starsCupTitles", starsCupTitles);
            teamData.put("titles", titleList);
            result.add(teamData);
        }

        result.sort((a, b) -> Integer.compare((int) b.get("totalTitles"), (int) a.get("totalTitles")));
        return result;
    }

    // ==================== 2. COMPETITION STATISTICS ====================

    /**
     * GET /stats/competition/{competitionId}/{seasonNumber}
     * Returns detailed statistics for a specific competition and season:
     * - topScorers: top 20 by goals (playerId, playerName, teamName, goals, assists, matches, avgRating)
     * - topAssisters: top 20 by assists
     * - topRated: top 20 by avg rating (min 5 matches)
     * - cleanSheets: teams ranked by clean sheets
     * - disciplinary: top 20 players by yellow + red cards
     */
    @GetMapping("/competition/{competitionId}/{seasonNumber}")
    public Map<String, Object> getCompetitionStats(@PathVariable long competitionId,
                                                    @PathVariable int seasonNumber) {
        Map<String, Object> result = new LinkedHashMap<>();

        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        result.put("competitionId", competitionId);
        result.put("competitionName", competition != null ? competition.getName() : "Unknown");
        result.put("seasonNumber", seasonNumber);

        // --- Player stats from Scorer ---
        List<Scorer> scorers = scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);

        Map<Long, int[]> playerAgg = new LinkedHashMap<>(); // playerId -> [goals, assists, matches]
        Map<Long, Double> playerRatingSum = new LinkedHashMap<>();
        Map<Long, Long> playerTeamId = new LinkedHashMap<>();
        Map<Long, String> playerTeamName = new LinkedHashMap<>();

        for (Scorer s : scorers) {
            long pid = s.getPlayerId();
            playerAgg.computeIfAbsent(pid, k -> new int[3]);
            playerAgg.get(pid)[0] += s.getGoals();
            playerAgg.get(pid)[1] += s.getAssists();
            playerAgg.get(pid)[2] += 1;
            playerRatingSum.merge(pid, s.getRating(), Double::sum);
            playerTeamId.putIfAbsent(pid, s.getTeamId());
            playerTeamName.putIfAbsent(pid, s.getTeamName());
        }

        // Build player entries
        List<Map<String, Object>> playerEntries = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : playerAgg.entrySet()) {
            long pid = e.getKey();
            int[] agg = e.getValue();
            Map<String, Object> pe = new LinkedHashMap<>();
            pe.put("playerId", pid);
            pe.put("playerName", humanRepository.findById(pid).map(Human::getName).orElse("Unknown"));
            pe.put("position", humanRepository.findById(pid).map(Human::getPosition).orElse(""));
            pe.put("teamId", playerTeamId.getOrDefault(pid, 0L));
            pe.put("teamName", playerTeamName.getOrDefault(pid, "Unknown"));
            pe.put("goals", agg[0]);
            pe.put("assists", agg[1]);
            pe.put("matches", agg[2]);
            pe.put("avgRating", agg[2] > 0 ? Math.round(playerRatingSum.getOrDefault(pid, 0.0) / agg[2] * 100.0) / 100.0 : 0);
            playerEntries.add(pe);
        }

        // Top scorers
        result.put("topScorers", playerEntries.stream()
                .sorted((a, b) -> Integer.compare((int) b.get("goals"), (int) a.get("goals")))
                .limit(20).toList());

        // Top assisters
        result.put("topAssisters", playerEntries.stream()
                .sorted((a, b) -> Integer.compare((int) b.get("assists"), (int) a.get("assists")))
                .limit(20).toList());

        // Top rated (min 5 matches)
        result.put("topRated", playerEntries.stream()
                .filter(pe -> (int) pe.get("matches") >= 5)
                .sorted((a, b) -> Double.compare((double) b.get("avgRating"), (double) a.get("avgRating")))
                .limit(20).toList());

        // --- Clean sheets per team ---
        // Group scorers by team, then count matches where opponent scored 0
        Map<Long, String> teamNames = new LinkedHashMap<>();
        Map<Long, Set<String>> teamMatchKeys = new LinkedHashMap<>();
        Map<Long, Integer> teamCleanSheets = new LinkedHashMap<>();
        Map<Long, Integer> teamMatchCounts = new LinkedHashMap<>();

        String lastKey = "";
        int counter = 0;
        for (Scorer s : scorers) {
            if (s.getTeamScore() < 0) continue;
            long tid = s.getTeamId();
            teamNames.putIfAbsent(tid, s.getTeamName());

            String candidateKey = tid + "_" + s.getOpponentTeamId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastKey)) { counter++; lastKey = candidateKey; }
            String matchKey = candidateKey + "_" + counter;

            Set<String> keys = teamMatchKeys.computeIfAbsent(tid, k -> new LinkedHashSet<>());
            if (keys.add(matchKey)) {
                teamMatchCounts.merge(tid, 1, Integer::sum);
                if (s.getOpponentScore() == 0) {
                    teamCleanSheets.merge(tid, 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> cleanSheetRanking = new ArrayList<>();
        for (Long tid : teamNames.keySet()) {
            Map<String, Object> cs = new LinkedHashMap<>();
            cs.put("teamId", tid);
            cs.put("teamName", teamNames.get(tid));
            int sheets = teamCleanSheets.getOrDefault(tid, 0);
            int matches = teamMatchCounts.getOrDefault(tid, 0);
            cs.put("cleanSheets", sheets);
            cs.put("matches", matches);
            cs.put("percentage", matches > 0 ? Math.round((double) sheets / matches * 100.0) / 100.0 : 0);
            cleanSheetRanking.add(cs);
        }
        cleanSheetRanking.sort((a, b) -> Integer.compare((int) b.get("cleanSheets"), (int) a.get("cleanSheets")));
        result.put("cleanSheets", cleanSheetRanking);

        // --- Disciplinary (cards from MatchEvent) ---
        List<MatchEvent> events = matchEventRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);

        Map<Long, int[]> playerCards = new LinkedHashMap<>(); // playerId -> [yellow, red]
        Map<Long, String> cardPlayerNames = new LinkedHashMap<>();
        Map<Long, Long> cardPlayerTeamIds = new LinkedHashMap<>();

        for (MatchEvent event : events) {
            if ("yellow_card".equals(event.getEventType())) {
                playerCards.computeIfAbsent(event.getPlayerId(), k -> new int[2])[0]++;
                cardPlayerNames.putIfAbsent(event.getPlayerId(), event.getPlayerName());
                cardPlayerTeamIds.putIfAbsent(event.getPlayerId(), event.getTeamId());
            } else if ("red_card".equals(event.getEventType())) {
                playerCards.computeIfAbsent(event.getPlayerId(), k -> new int[2])[1]++;
                cardPlayerNames.putIfAbsent(event.getPlayerId(), event.getPlayerName());
                cardPlayerTeamIds.putIfAbsent(event.getPlayerId(), event.getTeamId());
            }
        }

        List<Map<String, Object>> disciplinary = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : playerCards.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("playerId", e.getKey());
            d.put("playerName", cardPlayerNames.getOrDefault(e.getKey(), "Unknown"));
            d.put("teamId", cardPlayerTeamIds.getOrDefault(e.getKey(), 0L));
            d.put("teamName", teamRepository.findNameById(cardPlayerTeamIds.getOrDefault(e.getKey(), 0L)));
            d.put("yellowCards", e.getValue()[0]);
            d.put("redCards", e.getValue()[1]);
            d.put("totalCards", e.getValue()[0] + e.getValue()[1]);
            disciplinary.add(d);
        }
        disciplinary.sort((a, b) -> Integer.compare((int) b.get("totalCards"), (int) a.get("totalCards")));
        result.put("disciplinary", disciplinary.stream().limit(20).toList());

        return result;
    }

    // ==================== 3. PLAYER FORM (last 5 matches) ====================

    /**
     * GET /stats/playerForm/{playerId}
     * Returns the player's last 5 match appearances with individual performance data.
     * Response: { playerId, playerName, position, teamName,
     *             form: [{ opponentName, competitionName, teamScore, opponentScore,
     *                      result, goals, assists, rating }],
     *             avgRating, totalGoals, totalAssists }
     */
    @GetMapping("/playerForm/{playerId}")
    public Map<String, Object> getPlayerForm(@PathVariable long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Human player = humanRepository.findById(playerId).orElse(null);
        result.put("playerId", playerId);
        result.put("playerName", player != null ? player.getName() : "Unknown");
        result.put("position", player != null ? player.getPosition() : "");
        result.put("teamName", player != null && player.getTeamId() != null
                ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");

        // Get last 5 appearances (most recent by ID descending)
        List<Scorer> recentMatches = scorerRepository.findTop5ByPlayerIdOrderByIdDesc(playerId);

        List<Map<String, Object>> formList = new ArrayList<>();
        double ratingSum = 0;
        int totalGoals = 0, totalAssists = 0;

        for (Scorer s : recentMatches) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("opponentName", s.getOpponentTeamName());
            match.put("competitionName", s.getCompetitionName());
            match.put("seasonNumber", s.getSeasonNumber());
            match.put("teamScore", s.getTeamScore());
            match.put("opponentScore", s.getOpponentScore());

            String matchResult;
            if (s.getTeamScore() > s.getOpponentScore()) matchResult = "W";
            else if (s.getTeamScore() == s.getOpponentScore()) matchResult = "D";
            else matchResult = "L";
            match.put("result", matchResult);

            match.put("goals", s.getGoals());
            match.put("assists", s.getAssists());
            match.put("rating", Math.round(s.getRating() * 10.0) / 10.0);
            match.put("wasSubstitute", s.isSubstitute());

            formList.add(match);
            ratingSum += s.getRating();
            totalGoals += s.getGoals();
            totalAssists += s.getAssists();
        }

        // Reverse so oldest is first, newest is last (chronological order)
        Collections.reverse(formList);

        result.put("form", formList);
        result.put("matchCount", recentMatches.size());
        result.put("avgRating", recentMatches.isEmpty() ? 0 : Math.round(ratingSum / recentMatches.size() * 10.0) / 10.0);
        result.put("totalGoals", totalGoals);
        result.put("totalAssists", totalAssists);

        // Form trend: rating of last 5 vs previous 5
        List<Scorer> allMatches = scorerRepository.findAllByPlayerId(playerId);
        if (allMatches.size() >= 10) {
            double last5Avg = allMatches.subList(allMatches.size() - 5, allMatches.size()).stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0);
            double prev5Avg = allMatches.subList(allMatches.size() - 10, allMatches.size() - 5).stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0);
            String trend;
            if (last5Avg - prev5Avg > 3) trend = "IMPROVING";
            else if (prev5Avg - last5Avg > 3) trend = "DECLINING";
            else trend = "STABLE";
            result.put("trend", trend);
            result.put("last5Avg", Math.round(last5Avg * 10.0) / 10.0);
            result.put("prev5Avg", Math.round(prev5Avg * 10.0) / 10.0);
        } else {
            result.put("trend", "UNKNOWN");
        }

        return result;
    }

    // ==================== 4. PLAYER COMPARISON ====================

    /**
     * GET /stats/compare/{playerId1}/{playerId2}
     * Head-to-head comparison of two players across all seasons and competitions.
     * Response: { player1: {...}, player2: {...} } with identical stat structures for easy side-by-side display.
     * Each player object: { playerId, playerName, position, age, rating, teamName,
     *                        career: { matches, goals, assists, avgRating },
     *                        currentSeason: { matches, goals, assists, avgRating },
     *                        byCompetitionType: { league: {...}, cup: {...}, ... },
     *                        form: [last 5 ratings] }
     */
    @GetMapping("/compare/{playerId1}/{playerId2}")
    public Map<String, Object> comparePlayersHeadToHead(@PathVariable long playerId1,
                                                         @PathVariable long playerId2) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player1", buildComparisonProfile(playerId1));
        result.put("player2", buildComparisonProfile(playerId2));
        return result;
    }

    private Map<String, Object> buildComparisonProfile(long playerId) {
        Map<String, Object> profile = new LinkedHashMap<>();

        Human player = humanRepository.findById(playerId).orElse(null);
        profile.put("playerId", playerId);
        profile.put("playerName", player != null ? player.getName() : "Unknown");
        profile.put("position", player != null ? player.getPosition() : "");
        profile.put("age", player != null ? player.getAge() : 0);
        profile.put("rating", player != null ? Math.round(player.getRating() * 10.0) / 10.0 : 0);
        profile.put("teamName", player != null && player.getTeamId() != null
                ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");
        profile.put("morale", player != null ? Math.round(player.getMorale()) : 0);
        profile.put("fitness", player != null ? Math.round(player.getFitness()) : 0);

        List<Scorer> allScorers = scorerRepository.findAllByPlayerId(playerId);

        // Career totals
        int totalMatches = allScorers.size();
        int totalGoals = allScorers.stream().mapToInt(Scorer::getGoals).sum();
        int totalAssists = allScorers.stream().mapToInt(Scorer::getAssists).sum();
        double avgRating = allScorers.stream().mapToDouble(Scorer::getRating).average().orElse(0);

        Map<String, Object> career = new LinkedHashMap<>();
        career.put("matches", totalMatches);
        career.put("goals", totalGoals);
        career.put("assists", totalAssists);
        career.put("avgRating", Math.round(avgRating * 10.0) / 10.0);
        career.put("goalsPerGame", totalMatches > 0 ? Math.round((double) totalGoals / totalMatches * 100.0) / 100.0 : 0);
        profile.put("career", career);

        // Current season stats
        int maxSeason = allScorers.stream().mapToInt(Scorer::getSeasonNumber).max().orElse(0);
        List<Scorer> currentSeasonScorers = allScorers.stream()
                .filter(s -> s.getSeasonNumber() == maxSeason).toList();

        Map<String, Object> currentSeason = new LinkedHashMap<>();
        currentSeason.put("season", maxSeason);
        currentSeason.put("matches", currentSeasonScorers.size());
        currentSeason.put("goals", currentSeasonScorers.stream().mapToInt(Scorer::getGoals).sum());
        currentSeason.put("assists", currentSeasonScorers.stream().mapToInt(Scorer::getAssists).sum());
        currentSeason.put("avgRating", Math.round(currentSeasonScorers.stream()
                .mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
        profile.put("currentSeason", currentSeason);

        // By competition type
        Map<String, Object> byType = new LinkedHashMap<>();
        Map<Integer, List<Scorer>> byCompType = allScorers.stream()
                .collect(Collectors.groupingBy(Scorer::getCompetitionTypeId));
        String[] typeNames = {"", "League", "Cup", "Second League", "League of Champions", "Stars Cup"};
        for (Map.Entry<Integer, List<Scorer>> e : byCompType.entrySet()) {
            int typeId = e.getKey();
            List<Scorer> typeScorers = e.getValue();
            Map<String, Object> typeStats = new LinkedHashMap<>();
            typeStats.put("matches", typeScorers.size());
            typeStats.put("goals", typeScorers.stream().mapToInt(Scorer::getGoals).sum());
            typeStats.put("assists", typeScorers.stream().mapToInt(Scorer::getAssists).sum());
            typeStats.put("avgRating", Math.round(typeScorers.stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
            String label = typeId >= 0 && typeId < typeNames.length ? typeNames[typeId] : "Other";
            byType.put(label, typeStats);
        }
        profile.put("byCompetitionType", byType);

        // Season-by-season breakdown
        Map<Integer, List<Scorer>> bySeason = allScorers.stream()
                .collect(Collectors.groupingBy(Scorer::getSeasonNumber));
        List<Map<String, Object>> seasonBreakdown = new ArrayList<>();
        for (Map.Entry<Integer, List<Scorer>> e : bySeason.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<Scorer> ss = e.getValue();
            Map<String, Object> sb = new LinkedHashMap<>();
            sb.put("season", e.getKey());
            sb.put("matches", ss.size());
            sb.put("goals", ss.stream().mapToInt(Scorer::getGoals).sum());
            sb.put("assists", ss.stream().mapToInt(Scorer::getAssists).sum());
            sb.put("avgRating", Math.round(ss.stream().mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
            seasonBreakdown.add(sb);
        }
        profile.put("seasons", seasonBreakdown);

        // Last 5 match ratings (for form chart)
        List<Scorer> last5 = allScorers.size() > 5
                ? allScorers.subList(allScorers.size() - 5, allScorers.size()) : allScorers;
        profile.put("recentRatings", last5.stream()
                .map(s -> Math.round(s.getRating() * 10.0) / 10.0).toList());

        return profile;
    }

    // ==================== PRIVATE HELPERS ====================

    private ScorerLeaderboardEntry buildAllStatsForPlayer(Human player, Map<Integer, ScorerEntry> scorerEntryMap) {

        ScorerLeaderboardEntry scorerLeaderboardEntry = new ScorerLeaderboardEntry();

        scorerLeaderboardEntry.setName(player.getName());
        if (player.getTeamId() != null) {
            scorerLeaderboardEntry.setTeamName(teamRepository.findNameById(player.getTeamId()));
        } else {
            scorerLeaderboardEntry.setTeamName("Free Agent");
        }
        if (player.isRetired()) {
            scorerLeaderboardEntry.setTeamName("Retired");
        }
        scorerLeaderboardEntry.setPosition(player.getPosition());
        scorerLeaderboardEntry.setActive(!player.isRetired());
        scorerLeaderboardEntry.setBestEverRating(player.getBestEverRating());
        scorerLeaderboardEntry.setAge(player.getAge());
        scorerLeaderboardEntry.setCurrentRating(player.getRating());
        scorerLeaderboardEntry.setSeasonOfBestEverRating(player.getSeasonOfBestEverRating());

        for (Integer season: scorerEntryMap.keySet()) {

            ScorerEntry scorerEntry = scorerEntryMap.get(season);

            scorerLeaderboardEntry.setMatches(scorerLeaderboardEntry.getMatches() + scorerEntry.getTotalGames());
            scorerLeaderboardEntry.setGoals(scorerLeaderboardEntry.getGoals() + scorerEntry.getTotalGoals());

            for (CompetitionEntry competitionEntry: scorerEntry.getCompetitionEntries()) {
                if (competitionEntry.getCompetitionId() == 1 || competitionEntry.getCompetitionId() == 3) {
                    scorerLeaderboardEntry.setLeagueGoals(scorerLeaderboardEntry.getLeagueGoals() + competitionEntry.getGoals());
                    scorerLeaderboardEntry.setLeagueMatches(scorerLeaderboardEntry.getLeagueMatches() + competitionEntry.getGames());
                } else if (competitionEntry.getCompetitionId() == 2 || competitionEntry.getCompetitionId() == 4) {
                    scorerLeaderboardEntry.setCupGoals(scorerLeaderboardEntry.getCupGoals() + competitionEntry.getGoals());
                    scorerLeaderboardEntry.setCupMatches(scorerLeaderboardEntry.getCupMatches() + competitionEntry.getGames());
                } else {
                    scorerLeaderboardEntry.setSecondLeagueGoals(scorerLeaderboardEntry.getSecondLeagueGoals() + competitionEntry.getGoals());
                    scorerLeaderboardEntry.setSecondLeagueMatches(scorerLeaderboardEntry.getSecondLeagueMatches() + competitionEntry.getGames());
                }
            }
        }

        return scorerLeaderboardEntry;
    }
}
