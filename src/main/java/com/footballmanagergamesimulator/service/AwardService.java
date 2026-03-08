package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AwardService {

    @Autowired
    AwardRepository awardRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;

    public List<Award> processAwardsCeremony(int season) {

        List<Award> awards = new ArrayList<>();

        List<Scorer> allScorers = scorerRepository.findAllBySeasonNumber(season);

        // Best Player Award: highest combined goals + assists
        Award bestPlayer = determineBestPlayer(allScorers, season);
        if (bestPlayer != null) {
            awards.add(bestPlayer);
        }

        // Top Scorer Award: most goals
        Award topScorer = determineTopScorer(allScorers, season);
        if (topScorer != null) {
            awards.add(topScorer);
        }

        // Best Young Player: best rated player under 21
        Award bestYoungPlayer = determineBestYoungPlayer(season);
        if (bestYoungPlayer != null) {
            awards.add(bestYoungPlayer);
        }

        // Manager of the Year: team that overperformed most
        Award managerOfYear = determineManagerOfYear(season);
        if (managerOfYear != null) {
            awards.add(managerOfYear);
        }

        // Save all awards
        awardRepository.saveAll(awards);

        // Send inbox messages to human managers if their players won
        notifyManagersOfAwards(awards, season);

        return awards;
    }

    public List<Award> getAwardsForSeason(int season) {

        return awardRepository.findAllBySeasonNumber(season);
    }

    public List<Award> getPlayerAwards(long playerId) {

        return awardRepository.findAllByWinnerId(playerId);
    }

    private Award determineBestPlayer(List<Scorer> allScorers, int season) {

        if (allScorers.isEmpty()) {
            return null;
        }

        // Aggregate goals + assists per player
        Map<Long, int[]> playerStats = new HashMap<>(); // playerId -> [goals, assists]
        Map<Long, String> playerTeamNames = new HashMap<>();
        Map<Long, Long> playerTeamIds = new HashMap<>();

        for (Scorer scorer : allScorers) {
            playerStats.computeIfAbsent(scorer.getPlayerId(), k -> new int[2]);
            playerStats.get(scorer.getPlayerId())[0] += scorer.getGoals();
            playerStats.get(scorer.getPlayerId())[1] += scorer.getAssists();
            playerTeamNames.put(scorer.getPlayerId(), scorer.getTeamName());
            playerTeamIds.put(scorer.getPlayerId(), scorer.getTeamId());
        }

        // Find player with highest combined goals + assists
        long bestPlayerId = -1;
        int bestCombined = -1;

        for (Map.Entry<Long, int[]> entry : playerStats.entrySet()) {
            int combined = entry.getValue()[0] + entry.getValue()[1];
            if (combined > bestCombined) {
                bestCombined = combined;
                bestPlayerId = entry.getKey();
            }
        }

        if (bestPlayerId == -1) {
            return null;
        }

        String playerName = humanRepository.findById(bestPlayerId)
                .map(Human::getName)
                .orElse("Unknown Player");

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("BEST_PLAYER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestPlayerId);
        award.setWinnerName(playerName);
        award.setWinnerTeamId(playerTeamIds.getOrDefault(bestPlayerId, 0L));
        award.setWinnerTeamName(playerTeamNames.getOrDefault(bestPlayerId, "Unknown"));
        int[] stats = playerStats.get(bestPlayerId);
        award.setValue(stats[0] + " goals, " + stats[1] + " assists");

        return award;
    }

    private Award determineTopScorer(List<Scorer> allScorers, int season) {

        if (allScorers.isEmpty()) {
            return null;
        }

        // Aggregate goals per player
        Map<Long, Integer> playerGoals = new HashMap<>();
        Map<Long, String> playerTeamNames = new HashMap<>();
        Map<Long, Long> playerTeamIds = new HashMap<>();

        for (Scorer scorer : allScorers) {
            playerGoals.merge(scorer.getPlayerId(), scorer.getGoals(), Integer::sum);
            playerTeamNames.put(scorer.getPlayerId(), scorer.getTeamName());
            playerTeamIds.put(scorer.getPlayerId(), scorer.getTeamId());
        }

        // Find player with most goals
        long topScorerId = -1;
        int topGoals = -1;

        for (Map.Entry<Long, Integer> entry : playerGoals.entrySet()) {
            if (entry.getValue() > topGoals) {
                topGoals = entry.getValue();
                topScorerId = entry.getKey();
            }
        }

        if (topScorerId == -1) {
            return null;
        }

        String playerName = humanRepository.findById(topScorerId)
                .map(Human::getName)
                .orElse("Unknown Player");

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("TOP_SCORER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(topScorerId);
        award.setWinnerName(playerName);
        award.setWinnerTeamId(playerTeamIds.getOrDefault(topScorerId, 0L));
        award.setWinnerTeamName(playerTeamNames.getOrDefault(topScorerId, "Unknown"));
        award.setValue(topGoals + " goals");

        return award;
    }

    private Award determineBestYoungPlayer(int season) {

        // Find all players under 21 with typeId = PLAYER_TYPE
        List<Human> youngPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)
                .stream()
                .filter(h -> h.getAge() < 21 && !h.isRetired())
                .collect(Collectors.toList());

        if (youngPlayers.isEmpty()) {
            return null;
        }

        // Find the best rated young player
        Human bestYoung = youngPlayers.stream()
                .max(Comparator.comparingDouble(Human::getRating))
                .orElse(null);

        if (bestYoung == null) {
            return null;
        }

        String teamName = bestYoung.getTeamId() != null
                ? humanRepository.findById(bestYoung.getId()).map(h -> "Team " + h.getTeamId()).orElse("Unknown")
                : "Free Agent";

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("BEST_YOUNG_PLAYER");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestYoung.getId());
        award.setWinnerName(bestYoung.getName());
        award.setWinnerTeamId(bestYoung.getTeamId() != null ? bestYoung.getTeamId() : 0L);
        award.setWinnerTeamName(teamName);
        award.setValue("Rating: " + String.format("%.1f", bestYoung.getRating()));

        return award;
    }

    private Award determineManagerOfYear(int season) {

        // Get all managers
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)
                .stream()
                .filter(m -> m.getTeamId() != null && !m.isRetired())
                .collect(Collectors.toList());

        if (managers.isEmpty()) {
            return null;
        }

        // For each manager's team, compare their final league position against the team's reputation
        // The manager whose team overperformed the most wins
        Human bestManager = null;
        int bestOverperformance = Integer.MIN_VALUE;

        for (Human manager : managers) {
            long teamId = manager.getTeamId();

            // Get the team's reputation to estimate expected position
            Optional<Team> teamOpt = humanRepository.findById(teamId)
                    .map(h -> null); // We need TeamRepository

            // Use CompetitionTeamInfo to find final standing
            List<CompetitionTeamInfo> teamInfos = competitionTeamInfoRepository
                    .findAllByTeamIdAndSeasonNumber(teamId, season);

            if (teamInfos.isEmpty()) {
                continue;
            }

            // Get team reputation as a proxy for expected performance
            Optional<Team> team = Optional.empty();
            try {
                // We'll use manager reputation as a simple proxy
                int expectedPosition = Math.max(1, 20 - (manager.getManagerReputation() / 100));
                int actualPosition = (int) teamInfos.get(0).getRound(); // position approximation

                int overperformance = expectedPosition - actualPosition; // higher is better
                if (overperformance > bestOverperformance) {
                    bestOverperformance = overperformance;
                    bestManager = manager;
                }
            } catch (Exception e) {
                // Skip this manager if data is incomplete
            }
        }

        if (bestManager == null && !managers.isEmpty()) {
            // Fallback: pick manager with highest reputation
            bestManager = managers.stream()
                    .max(Comparator.comparingInt(Human::getManagerReputation))
                    .orElse(null);
        }

        if (bestManager == null) {
            return null;
        }

        Award award = new Award();
        award.setSeasonNumber(season);
        award.setAwardType("MANAGER_OF_YEAR");
        award.setCompetitionId(0);
        award.setCompetitionName("All Competitions");
        award.setWinnerId(bestManager.getId());
        award.setWinnerName(bestManager.getName());
        award.setWinnerTeamId(bestManager.getTeamId() != null ? bestManager.getTeamId() : 0L);
        award.setWinnerTeamName("Team " + (bestManager.getTeamId() != null ? bestManager.getTeamId() : 0));
        award.setValue("Reputation: " + bestManager.getManagerReputation());

        return award;
    }

    private void notifyManagersOfAwards(List<Award> awards, int season) {

        // Get all human managers (those with teams)
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)
                .stream()
                .filter(m -> m.getTeamId() != null)
                .collect(Collectors.toList());

        for (Human manager : managers) {
            long managedTeamId = manager.getTeamId();

            // Check if any awards were won by players on this manager's team
            List<Award> teamAwards = awards.stream()
                    .filter(a -> a.getWinnerTeamId() == managedTeamId)
                    .collect(Collectors.toList());

            if (!teamAwards.isEmpty()) {
                StringBuilder content = new StringBuilder("Congratulations! The following awards were won:\n\n");
                for (Award award : teamAwards) {
                    content.append("- ").append(formatAwardType(award.getAwardType()))
                            .append(": ").append(award.getWinnerName())
                            .append(" (").append(award.getValue()).append(")\n");
                }

                sendInboxMessage(managedTeamId, season, 0,
                        "Awards Ceremony Results",
                        content.toString(),
                        "AWARDS");
            }
        }
    }

    private String formatAwardType(String awardType) {

        switch (awardType) {
            case "BEST_PLAYER": return "Best Player";
            case "TOP_SCORER": return "Top Scorer";
            case "BEST_YOUNG_PLAYER": return "Best Young Player";
            case "MANAGER_OF_YEAR": return "Manager of the Year";
            default: return awardType;
        }
    }

    private void sendInboxMessage(long teamId, int season, int day, String title, String content, String category) {

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }
}
