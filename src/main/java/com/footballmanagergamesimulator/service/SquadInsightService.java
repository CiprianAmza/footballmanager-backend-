package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class SquadInsightService {

    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final RoundRepository roundRepository;
    private final PlayerPreviewService previewService;
    private final PlayerPersonalityService personalityService;

    public SquadInsightService(HumanRepository humanRepository,
                               TeamRepository teamRepository,
                               RoundRepository roundRepository,
                               PlayerPreviewService previewService,
                               PlayerPersonalityService personalityService) {
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.roundRepository = roundRepository;
        this.previewService = previewService;
        this.personalityService = personalityService;
    }

    public SquadOverview overview(long teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow();
        int season = roundRepository.findById(1L).map(row -> (int) row.getSeason()).orElse(1);
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .sorted(Comparator.comparing(Human::getPosition,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Human::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        Map<Long, PlayerPreviewService.Preview> previews = previewService.previews(players, season);
        List<Team> destinations = teamRepository.findAll().stream()
                .filter(candidate -> candidate.getId() != teamId)
                .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparing(Team::getName))
                .toList();

        List<PlayerInsight> insights = players.stream()
                .map(player -> toInsight(player, team, previews.get(player.getId()), destinations, season))
                .toList();
        return new SquadOverview(team.getId(), team.getName(), season, insights);
    }

    private PlayerInsight toInsight(Human player, Team team, PlayerPreviewService.Preview preview,
                                    List<Team> destinations, int season) {
        PlayerPersonalityService.Profile profile = personalityService.profile(player);
        int readiness = clamp((int) Math.round(player.getFitness() * .58 + player.getMorale() * .32
                + profile.consistency() * .5));
        int transferRiskScore = transferRisk(player, profile, team, season);
        String transferRisk = transferRiskScore >= 70 ? "HIGH" : transferRiskScore >= 42 ? "MEDIUM" : "LOW";
        Team desiredTeam = player.isWantsTransfer() ? desiredTeam(player, team, destinations) : null;

        String concern = concern(player, season);
        String nextAction = nextAction(player, transferRisk, concern);
        String destinationPreference = destinationPreference(player, profile, team);
        PlayerPreviewService.Preview safePreview = preview == null
                ? new PlayerPreviewService.Preview(0, 0, 0, List.of()) : preview;

        return new PlayerInsight(
                player.getId(), player.getName(), player.getPosition(), player.getAge(),
                round(player.getRating()), player.getCurrentAbility(), player.getPotentialAbility(),
                round(player.getFitness()), round(player.getMorale()), readiness,
                player.getTransferValue(), player.getWage(), player.getContractEndSeason(),
                player.getAgreedPlayingTime(), safePreview.appearances(), safePreview.goals(), safePreview.assists(),
                player.getConsecutiveBenched(), player.isWantsTransfer(), player.isWillNeverLeave(),
                player.getIndividualTrainingFocus(), player.getIndividualTrainingAttribute(),
                player.getIndividualTrainingRole(), profile, transferRisk, transferRiskScore,
                destinationPreference, desiredTeam == null ? null : desiredTeam.getId(),
                desiredTeam == null ? null : desiredTeam.getName(), concern, nextAction);
    }

    private int transferRisk(Human player, PlayerPersonalityService.Profile profile, Team team, int season) {
        if (player.isWillNeverLeave()) return 0;
        int risk = player.isWantsTransfer() ? 72 : 8;
        risk += Math.max(0, 60 - (int) player.getMorale()) / 2;
        risk += Math.min(18, player.getConsecutiveBenched() * 3);
        if (player.getContractEndSeason() <= season + 1) risk += 18;
        if (profile.ambition() >= 16 && team.getReputation() < 700) risk += 12;
        risk -= Math.max(0, profile.loyalty() - 12);
        return clamp(risk);
    }

    private Team desiredTeam(Human player, Team current, List<Team> teams) {
        List<Team> stronger = teams.stream()
                .filter(team -> team.getReputation() >= current.getReputation())
                .toList();
        List<Team> pool = stronger.isEmpty() ? teams : stronger;
        if (pool.isEmpty()) return null;
        return pool.get((int) Math.floorMod(player.getId() * 31L + 17, pool.size()));
    }

    private String destinationPreference(Human player, PlayerPersonalityService.Profile profile, Team team) {
        if (player.isWillNeverLeave()) return "Wants to finish his career at the club";
        if (player.isWantsTransfer()) return profile.adaptability() >= 13
                ? "Actively seeking a bigger club, including abroad"
                : "Actively seeking a stronger club in familiar surroundings";
        if (profile.loyalty() >= 17) return "Prefers to stay and build a legacy";
        if (profile.ambition() >= 17 && team.getReputation() < 800) return "Would consider an elite-club approach";
        if (profile.adaptability() >= 17) return "Open to playing abroad in the future";
        return "No current desire to leave";
    }

    private String concern(Human player, int season) {
        if (player.isWantsTransfer()) return "Has formally asked to leave";
        if (player.getConsecutiveBenched() >= 5) return "Needs a clearer route to playing time";
        if (player.getMorale() < 50) return "Morale requires immediate attention";
        if (player.getContractEndSeason() <= season + 1) return "Contract is entering its final season";
        if (player.getFitness() < 75) return "Condition is below match-ready level";
        return "No active concern";
    }

    private String nextAction(Human player, String risk, String concern) {
        if (player.isWantsTransfer()) return "Discuss his future and set a clear deadline";
        if (player.getConsecutiveBenched() >= 5) return "Review playing-time promise";
        if (concern.contains("Contract")) return "Open contract talks";
        if (player.getFitness() < 75) return "Use recovery training";
        if ("HIGH".equals(risk)) return "Schedule a private conversation";
        return "Continue current management plan";
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    private double round(double value) { return Math.round(value * 10.0) / 10.0; }

    public record SquadOverview(long teamId, String teamName, int season, List<PlayerInsight> players) { }

    public record PlayerInsight(
            long id, String name, String position, int age,
            double rating, int currentAbility, int potentialAbility,
            double fitness, double morale, int readiness,
            long transferValue, long wage, int contractEndSeason, String agreedPlayingTime,
            int appearances, int goals, int assists, int consecutiveBenched,
            boolean wantsTransfer, boolean willNeverLeave,
            String individualTrainingFocus, String individualTrainingAttribute, String individualTrainingRole,
            PlayerPersonalityService.Profile profile,
            String transferRisk, int transferRiskScore, String destinationPreference,
            Long desiredTeamId, String desiredTeamName, String concern, String nextAction) { }
}
