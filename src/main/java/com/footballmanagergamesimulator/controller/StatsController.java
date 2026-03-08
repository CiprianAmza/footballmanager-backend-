package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
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
