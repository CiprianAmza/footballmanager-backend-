package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Assistant Manager AI service.
 * Provides tactical recommendations, lineup suggestions, opponent analysis,
 * and transfer advice — similar to FM's assistant manager functionality.
 * The quality of advice scales with the assistant's coaching attributes.
 */
@Service
public class AssistantManagerService {

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private InjuryRepository injuryRepository;
    @Autowired
    private PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private TacticController tacticController;
    @Autowired
    private TacticService tacticService;
    @Autowired
    private PlayerRoleService playerRoleService;
    @Autowired
    private InjuryTimelineService injuryTimelineService;
    @Autowired(required = false)
    private com.footballmanagergamesimulator.config.GameplayFeatureConfig gameplayFeatures;

    /**
     * Get the assistant manager for a team, if one exists.
     */
    private Human getAssistant(long teamId) {
        List<Human> assistants = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.ASSISTANT_MANAGER_TYPE);
        return assistants.isEmpty() ? null : assistants.get(0);
    }

    /**
     * Recommend the best formation for the current squad.
     * Evaluates all available formations and picks the one with highest total rating.
     */
    public Map<String, Object> suggestBestFormation(long teamId) {
        Human assistant = getAssistant(teamId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (assistant != null) {
            result.put("assistantName", assistant.getName());
            result.put("tacticalAbility", assistant.getCoachingTactical());
        }

        List<String> allTactics = tacticService.getAllExistingTactics();
        String bestTactic = "442";
        double bestRating = 0;
        List<Map<String, Object>> evaluations = new ArrayList<>();

        for (String tactic : allTactics) {
            List<PlayerView> bestEleven = tacticController.getBestEleven(String.valueOf(teamId), tactic);
            double rating = bestEleven.stream().mapToDouble(PlayerView::getRating).sum();

            Map<String, Object> eval = new LinkedHashMap<>();
            eval.put("formation", tactic);
            eval.put("totalRating", Math.round(rating * 10.0) / 10.0);
            evaluations.add(eval);

            if (rating > bestRating) {
                bestRating = rating;
                bestTactic = tactic;
            }
        }

        evaluations.sort((a, b) -> Double.compare((double) b.get("totalRating"), (double) a.get("totalRating")));

        result.put("recommended", bestTactic);
        result.put("recommendedRating", Math.round(bestRating * 10.0) / 10.0);
        result.put("allFormations", evaluations);
        result.put("advice", String.format("I recommend using a %s formation. It gives us our strongest possible XI with a combined rating of %.1f.",
                bestTactic, bestRating));

        return result;
    }

    /**
     * Identify issues with the current lineup:
     * - Injured players still in the formation
     * - Players with low morale
     * - Players out of position
     * - Low fitness players
     * - Contract expiring soon
     */
    public Map<String, Object> suggestLineupChanges(long teamId) {
        Human assistant = getAssistant(teamId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (assistant != null) {
            result.put("assistantName", assistant.getName());
        }

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        Set<Long> injuredIds = availabilityDisabled() ? Set.of()
                : injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream().map(Injury::getPlayerId).collect(Collectors.toSet());

        int currentSeason = (int) roundRepository.findById(1L).orElse(new Round()).getSeason();
        InjuryTimelineService.GameDate currentDate = injuryTimelineService.currentDate();

        List<Map<String, Object>> concerns = new ArrayList<>();

        for (Human player : players) {
            // Injured
            if (injuredIds.contains(player.getId())) {
                Injury injury = injuryRepository.findByPlayerIdAndDaysRemainingGreaterThan(player.getId(), 0).orElse(null);
                Map<String, Object> concern = new LinkedHashMap<>();
                concern.put("playerId", player.getId());
                concern.put("playerName", player.getName());
                concern.put("position", player.getPosition());
                concern.put("type", "INJURED");
                concern.put("severity", "HIGH");
                concern.put("message", String.format("%s is injured (%s) — %d days remaining. Cannot play.",
                        player.getName(),
                        injury != null ? injury.getInjuryType() : "unknown",
                        injury != null ? injuryTimelineService.remainingDays(
                                injury, currentDate.season(), currentDate.day()) : 0));
                concerns.add(concern);
            }

            // Low morale
            if (player.getMorale() < 40) {
                Map<String, Object> concern = new LinkedHashMap<>();
                concern.put("playerId", player.getId());
                concern.put("playerName", player.getName());
                concern.put("position", player.getPosition());
                concern.put("type", "LOW_MORALE");
                concern.put("severity", player.getMorale() < 20 ? "HIGH" : "MEDIUM");
                concern.put("message", String.format("%s has very low morale (%.0f). Performance will suffer significantly.",
                        player.getName(), player.getMorale()));
                concerns.add(concern);
            }

            // Low fitness
            if (player.getFitness() < 70) {
                Map<String, Object> concern = new LinkedHashMap<>();
                concern.put("playerId", player.getId());
                concern.put("playerName", player.getName());
                concern.put("position", player.getPosition());
                concern.put("type", "LOW_FITNESS");
                concern.put("severity", player.getFitness() < 50 ? "HIGH" : "MEDIUM");
                concern.put("message", String.format("%s has low fitness (%.0f%%). Consider resting them.",
                        player.getName(), player.getFitness()));
                concerns.add(concern);
            }

            // Contract expiring
            if (player.getContractEndSeason() > 0 && player.getContractEndSeason() <= currentSeason) {
                Map<String, Object> concern = new LinkedHashMap<>();
                concern.put("playerId", player.getId());
                concern.put("playerName", player.getName());
                concern.put("position", player.getPosition());
                concern.put("type", "CONTRACT_EXPIRING");
                concern.put("severity", "LOW");
                concern.put("message", String.format("%s's contract expires this season. Consider renewing or selling.",
                        player.getName()));
                concerns.add(concern);
            }

            // Wants transfer
            if (player.isWantsTransfer()) {
                Map<String, Object> concern = new LinkedHashMap<>();
                concern.put("playerId", player.getId());
                concern.put("playerName", player.getName());
                concern.put("position", player.getPosition());
                concern.put("type", "WANTS_TRANSFER");
                concern.put("severity", "MEDIUM");
                concern.put("message", String.format("%s has requested a transfer. Their morale may drop if ignored.",
                        player.getName()));
                concerns.add(concern);
            }
        }

        // Sort by severity
        Map<String, Integer> severityOrder = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
        concerns.sort((a, b) -> Integer.compare(
                severityOrder.getOrDefault(a.get("severity"), 3),
                severityOrder.getOrDefault(b.get("severity"), 3)));

        result.put("concerns", concerns);
        result.put("totalConcerns", concerns.size());

        if (concerns.isEmpty()) {
            result.put("summary", "The squad looks good. No major concerns to report.");
        } else {
            long highCount = concerns.stream().filter(c -> "HIGH".equals(c.get("severity"))).count();
            result.put("summary", String.format("I've identified %d concern(s), %d of which are critical.",
                    concerns.size(), highCount));
        }

        return result;
    }

    private boolean availabilityDisabled() {
        return gameplayFeatures != null && gameplayFeatures.isPlayerAvailabilityDisabled();
    }

    /**
     * Pre-match analysis of the opponent.
     * Compares team strengths, identifies opponent weak positions, suggests approach.
     */
    public Map<String, Object> analyzeOpponent(long teamId, long opponentTeamId) {
        Human assistant = getAssistant(teamId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (assistant != null) {
            result.put("assistantName", assistant.getName());
        }

        Team myTeam = teamRepository.findById(teamId).orElse(null);
        Team opponent = teamRepository.findById(opponentTeamId).orElse(null);
        if (myTeam == null || opponent == null) {
            result.put("error", "Team not found");
            return result;
        }

        result.put("opponentName", opponent.getName());
        result.put("opponentReputation", opponent.getReputation());

        // Get opponent's players by position
        List<Human> oppPlayers = humanRepository.findAllByTeamIdAndTypeId(opponentTeamId, TypeNames.PLAYER_TYPE);
        Map<String, List<Human>> oppByPosition = oppPlayers.stream()
                .collect(Collectors.groupingBy(Human::getPosition));

        // Analyze each position group
        List<Map<String, Object>> positionAnalysis = new ArrayList<>();
        String[] positions = {"GK", "DC", "DL", "DR", "MC", "ML", "MR", "ST"};
        for (String pos : positions) {
            List<Human> playersInPos = oppByPosition.getOrDefault(pos, List.of());
            if (playersInPos.isEmpty()) continue;

            double avgRating = playersInPos.stream().mapToDouble(Human::getRating).average().orElse(0);
            double bestRating = playersInPos.stream().mapToDouble(Human::getRating).max().orElse(0);

            Map<String, Object> posMap = new LinkedHashMap<>();
            posMap.put("position", pos);
            posMap.put("count", playersInPos.size());
            posMap.put("averageRating", Math.round(avgRating * 10.0) / 10.0);
            posMap.put("bestPlayerRating", Math.round(bestRating * 10.0) / 10.0);

            if (playersInPos.size() > 0) {
                Human best = playersInPos.stream().max(Comparator.comparingDouble(Human::getRating)).get();
                posMap.put("bestPlayerName", best.getName());
            }

            // Strength assessment
            if (avgRating > 80) posMap.put("strength", "VERY_STRONG");
            else if (avgRating > 65) posMap.put("strength", "STRONG");
            else if (avgRating > 50) posMap.put("strength", "AVERAGE");
            else posMap.put("strength", "WEAK");

            positionAnalysis.add(posMap);
        }

        result.put("positionAnalysis", positionAnalysis);

        // Find weak spots
        List<String> weakPositions = positionAnalysis.stream()
                .filter(p -> "WEAK".equals(p.get("strength")) || "AVERAGE".equals(p.get("strength")))
                .map(p -> (String) p.get("position"))
                .toList();

        List<String> strongPositions = positionAnalysis.stream()
                .filter(p -> "VERY_STRONG".equals(p.get("strength")) || "STRONG".equals(p.get("strength")))
                .map(p -> (String) p.get("position"))
                .toList();

        // Compare overall power
        double myPower = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .mapToDouble(Human::getRating)
                .sorted().skip(Math.max(0, humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).size() - 11))
                .sum();
        double oppPower = oppPlayers.stream()
                .mapToDouble(Human::getRating)
                .sorted().skip(Math.max(0, oppPlayers.size() - 11))
                .sum();

        result.put("ourEstimatedPower", Math.round(myPower * 10.0) / 10.0);
        result.put("opponentEstimatedPower", Math.round(oppPower * 10.0) / 10.0);

        // Tactical recommendation
        String recommendedMentality;
        String reasoning;
        if (myPower > oppPower * 1.2) {
            recommendedMentality = "Attacking";
            reasoning = String.format("We are significantly stronger than %s. I suggest an attacking approach to dominate the game.", opponent.getName());
        } else if (myPower > oppPower * 1.05) {
            recommendedMentality = "Balanced";
            reasoning = String.format("We have a slight edge over %s. A balanced approach should serve us well.", opponent.getName());
        } else if (myPower > oppPower * 0.95) {
            recommendedMentality = "Balanced";
            reasoning = String.format("This looks like an even match against %s. Stay compact and take our chances.", opponent.getName());
        } else if (myPower > oppPower * 0.8) {
            recommendedMentality = "Defensive";
            reasoning = String.format("%s are stronger than us. I suggest a defensive approach, looking to hit them on the counter.", opponent.getName());
        } else {
            recommendedMentality = "Very Defensive";
            reasoning = String.format("%s are much stronger. We should sit deep, stay compact, and hope for a break.", opponent.getName());
        }

        result.put("recommendedMentality", recommendedMentality);
        result.put("weakPositions", weakPositions);
        result.put("strongPositions", strongPositions);

        // Build advice string
        StringBuilder advice = new StringBuilder(reasoning);
        if (!weakPositions.isEmpty()) {
            advice.append(String.format(" Their %s area looks vulnerable — we should target that.", String.join("/", weakPositions)));
        }
        if (!strongPositions.isEmpty()) {
            advice.append(String.format(" Watch out for their strength in %s.", String.join("/", strongPositions)));
        }
        result.put("advice", advice.toString());

        return result;
    }

    /**
     * Identify positions where the squad is weakest and needs reinforcement.
     * Useful for transfer window planning.
     */
    public Map<String, Object> suggestTransferTargets(long teamId) {
        Human assistant = getAssistant(teamId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (assistant != null) {
            result.put("assistantName", assistant.getName());
        }

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        Map<String, List<Human>> byPosition = players.stream()
                .collect(Collectors.groupingBy(Human::getPosition));

        String[] positions = {"GK", "DC", "DL", "DR", "MC", "ML", "MR", "ST"};
        Map<String, Integer> minNeeded = Map.of(
                "GK", 2, "DC", 4, "DL", 2, "DR", 2, "MC", 4, "ML", 2, "MR", 2, "ST", 3
        );

        List<Map<String, Object>> needs = new ArrayList<>();

        for (String pos : positions) {
            List<Human> posPlayers = byPosition.getOrDefault(pos, List.of());
            int count = posPlayers.size();
            int needed = minNeeded.getOrDefault(pos, 2);
            double avgRating = posPlayers.stream().mapToDouble(Human::getRating).average().orElse(0);
            double bestRating = posPlayers.stream().mapToDouble(Human::getRating).max().orElse(0);

            String priority = "NONE";
            String reason = "";

            if (count < needed) {
                priority = "HIGH";
                reason = String.format("Only %d player(s) for %s, need at least %d.", count, pos, needed);
            } else if (count == needed && avgRating < 50) {
                priority = "MEDIUM";
                reason = String.format("Bare minimum depth at %s and quality is low (avg %.1f).", pos, avgRating);
            } else if (avgRating < 40) {
                priority = "MEDIUM";
                reason = String.format("Quality at %s is poor (avg %.1f). Consider upgrading.", pos, avgRating);
            }

            // Check for aging players
            long agingCount = posPlayers.stream().filter(p -> p.getAge() >= 32).count();
            if (agingCount > 0 && priority.equals("NONE")) {
                priority = "LOW";
                reason = String.format("%d player(s) at %s are 32+. Plan for the future.", agingCount, pos);
            }

            if (!"NONE".equals(priority)) {
                Map<String, Object> need = new LinkedHashMap<>();
                need.put("position", pos);
                need.put("currentCount", count);
                need.put("minNeeded", needed);
                need.put("averageRating", Math.round(avgRating * 10.0) / 10.0);
                need.put("bestRating", Math.round(bestRating * 10.0) / 10.0);
                need.put("priority", priority);
                need.put("reason", reason);
                needs.add(need);
            }
        }

        // Sort by priority
        Map<String, Integer> priorityOrder = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
        needs.sort((a, b) -> Integer.compare(
                priorityOrder.getOrDefault(a.get("priority"), 3),
                priorityOrder.getOrDefault(b.get("priority"), 3)));

        result.put("needs", needs);

        if (needs.isEmpty()) {
            result.put("advice", "The squad looks well-balanced. No urgent signings needed.");
        } else {
            String topNeed = (String) needs.get(0).get("position");
            result.put("advice", String.format("Our most pressing need is at %s. %s",
                    topNeed, needs.get(0).get("reason")));
        }

        return result;
    }

    /**
     * Comprehensive pre-match briefing combining formation, lineup, and opponent analysis.
     */
    public Map<String, Object> getPreMatchBriefing(long teamId, long opponentTeamId) {
        Map<String, Object> briefing = new LinkedHashMap<>();

        Human assistant = getAssistant(teamId);
        if (assistant != null) {
            briefing.put("assistantName", assistant.getName());
            briefing.put("assistantTactical", assistant.getCoachingTactical());
        }

        briefing.put("formation", suggestBestFormation(teamId));
        briefing.put("lineupConcerns", suggestLineupChanges(teamId));
        briefing.put("opponentAnalysis", analyzeOpponent(teamId, opponentTeamId));

        return briefing;
    }
}
