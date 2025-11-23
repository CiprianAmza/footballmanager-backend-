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

@RestController
@RequestMapping("/stats")
@CrossOrigin(origins = "*")
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
            if (scorerLeaderboardEntry.isPresent())
                playerToScorerLeaderboard.put(player.getId(), scorerLeaderboardEntry.get());
        }

        return playerToScorerLeaderboard;
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
