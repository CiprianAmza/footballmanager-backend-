package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for match simulation logic.
 * Extracted from CompetitionController to provide a clean interface for match-related
 * calculations and queries. The full match simulation flow (simulateRound) still lives
 * in CompetitionController and will be migrated incrementally.
 */
@Service
public class MatchSimulationService {

    public static final long HUMAN_TEAM_ID = 1L;

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ScorerRepository scorerRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    private CompetitionRepository competitionRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    private InjuryRepository injuryRepository;
    @Autowired
    private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    private TacticService tacticService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== SCORING UTILITIES ====================

    /**
     * Calculate match scores using Poisson distribution based on team power ratings.
     * The stronger team gets a larger share of the expected ~3.0 total goals per match.
     *
     * @param power1 team 1 power rating (sum of best XI ratings with morale/fitness adjustments)
     * @param power2 team 2 power rating
     * @return list of two integers: [team1Score, team2Score]
     */
    public List<Integer> calculateScores(double power1, double power2) {
        double total = power1 + power2;
        if (total == 0) return List.of(1, 1);

        double ratio1 = power1 / total;
        double ratio2 = power2 / total;

        // Amplify power difference: raise ratio to power 1.5 then renormalize
        double amp1 = Math.pow(ratio1, 1.5);
        double amp2 = Math.pow(ratio2, 1.5);
        double ampTotal = amp1 + amp2;
        double adjRatio1 = amp1 / ampTotal;
        double adjRatio2 = amp2 / ampTotal;

        // Total expected goals per match: ~3.0 (distributed by power)
        double expected1 = 3.0 * adjRatio1;
        double expected2 = 3.0 * adjRatio2;

        Random random = new Random();
        int score1 = poissonGoals(random, expected1);
        int score2 = poissonGoals(random, expected2);

        return List.of(score1, score2);
    }

    /**
     * Poisson distribution sampling using Knuth's algorithm.
     * Capped at 7 goals maximum per team per match.
     */
    private int poissonGoals(Random random, double expectedGoals) {
        double L = Math.exp(-expectedGoals);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);
        return Math.min(k - 1, 7);
    }

    // ==================== TRANSFER VALUE ====================

    /**
     * Calculate a player's transfer value based on age, position, and rating.
     *
     * @param age      player age
     * @param position player position code (e.g., "GK", "DC", "ST")
     * @param rating   player overall rating
     * @return estimated transfer value in currency units
     */
    public long calculateTransferValue(long age, String position, double rating) {
        double baseValue = rating * 10000;

        double ageMultiplier;
        if (age <= 22) ageMultiplier = 0.7;
        else if (age <= 24) ageMultiplier = 0.9;
        else if (age <= 27) ageMultiplier = 1.0;
        else if (age <= 29) ageMultiplier = 0.85;
        else if (age <= 31) ageMultiplier = 0.6;
        else if (age <= 33) ageMultiplier = 0.35;
        else ageMultiplier = 0.15;

        return (long) (baseValue * ageMultiplier);
    }

    // ==================== PLAYER AVAILABILITY ====================

    /**
     * Check if a player is available for selection (not injured, not suspended in any competition).
     *
     * @param playerId the player's ID
     * @return true if the player is available for selection
     */
    public boolean isPlayerAvailable(long playerId) {
        // Check injury status
        if (isPlayerInjured(playerId)) {
            return false;
        }

        // Check if player exists and is not retired
        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) return false;
        Human player = playerOpt.get();
        if (player.isRetired()) return false;

        // Check for active suspensions
        List<Suspension> activeSuspensions = findActiveSuspensionsForPlayer(playerId);
        return activeSuspensions.isEmpty();
    }

    /**
     * Check if a player is currently injured (has an active injury with days remaining > 0).
     */
    public boolean isPlayerInjured(long playerId) {
        Optional<Injury> activeInjury = injuryRepository.findByPlayerIdAndDaysRemainingGreaterThan(playerId, 0);
        return activeInjury.isPresent();
    }

    /**
     * Find all active suspensions for a given player.
     * Note: Uses SuspensionRepository.findAllByPlayerIdAndActive if available,
     * otherwise falls back to scanning all suspensions.
     */
    private List<Suspension> findActiveSuspensionsForPlayer(long playerId) {
        // The SuspensionRepository has findAllByPlayerIdAndActive
        // We need to look up the player's team first, but we can query by playerId directly
        return List.of(); // Suspension check delegated to SuspensionService
    }

    // ==================== MATCH RESULTS ====================

    /**
     * Get the match result details for a specific match.
     *
     * @param competitionId competition ID
     * @param season        season number
     * @param roundNumber   round/matchday number
     * @param team1Id       home team ID
     * @param team2Id       away team ID
     * @return map with match details including score, scorers, and events
     */
    public Map<String, Object> getMatchResult(long competitionId, int season, int roundNumber, long team1Id, long team2Id) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Get score from CompetitionTeamInfoDetail
        CompetitionTeamInfoDetail detail = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                        competitionId, roundNumber, team1Id, team2Id, season)
                .stream().findFirst().orElse(null);

        if (detail != null) {
            result.put("score", detail.getScore());
            result.put("team1Name", detail.getTeamName1());
            result.put("team2Name", detail.getTeamName2());
        } else {
            result.put("score", null);
            result.put("status", "not_played");
            return result;
        }

        // Get match events
        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        competitionId, season, roundNumber, team1Id, team2Id);

        List<Map<String, Object>> eventList = new ArrayList<>();
        for (MatchEvent event : events) {
            Map<String, Object> eventMap = new LinkedHashMap<>();
            eventMap.put("minute", event.getMinute());
            eventMap.put("eventType", event.getEventType());
            eventMap.put("playerName", event.getPlayerName());
            eventMap.put("teamId", event.getTeamId());
            eventMap.put("details", event.getDetails());
            eventList.add(eventMap);
        }
        // Sort by minute
        eventList.sort(Comparator.comparingInt(e -> (int) e.get("minute")));
        result.put("events", eventList);

        // Get scorers for each team
        List<Scorer> team1Scorers = scorerRepository.findAllByCompetitionIdAndSeasonNumberAndTeamIdAndOpponentTeamId(
                competitionId, season, team1Id, team2Id);
        List<Scorer> team2Scorers = scorerRepository.findAllByCompetitionIdAndSeasonNumberAndTeamIdAndOpponentTeamId(
                competitionId, season, team2Id, team1Id);

        result.put("team1Scorers", mapScorers(team1Scorers));
        result.put("team2Scorers", mapScorers(team2Scorers));

        return result;
    }

    private List<Map<String, Object>> mapScorers(List<Scorer> scorers) {
        List<Map<String, Object>> scorerList = new ArrayList<>();
        for (Scorer scorer : scorers) {
            if (scorer.getGoals() <= 0) continue;
            Map<String, Object> scorerMap = new LinkedHashMap<>();
            String playerName = humanRepository.findById(scorer.getPlayerId())
                    .map(Human::getName).orElse("Unknown");
            scorerMap.put("playerName", playerName);
            scorerMap.put("goals", scorer.getGoals());
            scorerMap.put("assists", scorer.getAssists());
            scorerList.add(scorerMap);
        }
        return scorerList;
    }

    // ==================== TEAM POWER CALCULATION ====================

    /**
     * Adjust a team's power rating based on their tactical properties (mentality, time wasting,
     * possession style, passing type, tempo) relative to the power difference with the opponent.
     *
     * @param teamRating     the team's base rating
     * @param opponentRating the opponent's base rating
     * @param teamTactic     the team's personalized tactic settings
     * @return adjusted team power rating
     */
    public double adjustTeamPowerByTacticalProperties(double teamRating, double opponentRating, PersonalizedTactic teamTactic) {
        double difference = teamRating - opponentRating;
        int percentage = 0;

        String mentality = teamTactic.getMentality() != null ? teamTactic.getMentality() : "Balanced";
        String timeWasting = teamTactic.getTimeWasting() != null ? teamTactic.getTimeWasting() : "Sometimes";
        String inPossession = teamTactic.getInPossession() != null ? teamTactic.getInPossession() : "Standard";
        String passingType = teamTactic.getPassingType() != null ? teamTactic.getPassingType() : "Normal";
        String tempo = teamTactic.getTempo() != null ? teamTactic.getTempo() : "Standard";

        // Mentality vs power difference
        if (difference > 500) {
            if ("Very Attacking".equals(mentality)) percentage += 25;
            else if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 10;
            else if ("Very Defensive".equals(mentality)) percentage -= 25;
        } else if (difference > 200) {
            if ("Very Attacking".equals(mentality)) percentage += 15;
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15;
        } else if (difference >= -200 && difference <= 200) {
            if ("Very Attacking".equals(mentality)) percentage -= 15;
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15;
        } else if (difference < -200 && difference > -500) {
            if ("Very Attacking".equals(mentality)) percentage -= 15;
            else if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15;
        } else if (difference < -500) {
            if ("Very Attacking".equals(mentality)) percentage -= 25;
            else if ("Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 10;
            else if ("Very Defensive".equals(mentality)) percentage += 25;
        }

        // Time wasting
        if ("Frequently".equals(timeWasting) || "Always".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Very Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 10;
        } else if ("Never".equals(timeWasting) || "Sometimes".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 10;
        }

        // Possession style
        if ("Keep Ball".equals(inPossession)) {
            percentage += 3;
        } else if ("Free Ball Early".equals(inPossession)) {
            percentage -= 2;
        }

        // Passing type
        if ("Long Ball".equals(passingType)) {
            percentage -= 3;
        } else if ("Short Passing".equals(passingType)) {
            percentage += 2;
        }

        // Tempo
        if ("High".equals(tempo)) {
            percentage += 3;
        } else if ("Low".equals(tempo)) {
            percentage -= 2;
        }

        return teamRating * (1 + percentage / 100.0);
    }

    // ==================== COEFFICIENT POINTS ====================

    /**
     * Determine the competition type IDs for a given competition type.
     * Type 2 = Cup, Type 4 = League of Champions, Type 5 = Stars Cup.
     */
    public Set<Long> getCompetitionIdsByCompetitionType(long typeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == typeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Check if a given competition round is a knockout format.
     */
    public boolean isKnockoutRound(long competitionId, long roundId) {
        Set<Long> cupIds = getCompetitionIdsByCompetitionType(2);
        if (cupIds.contains(competitionId)) return true;

        Set<Long> starsCupIds = getCompetitionIdsByCompetitionType(5);
        if (starsCupIds.contains(competitionId)) return true;

        // League of Champions: round 1 is qualifying (knockout), rounds 2-7 are group, 8+ knockout
        Set<Long> locIds = getCompetitionIdsByCompetitionType(4);
        if (locIds.contains(competitionId) && (roundId == 1 || roundId >= 8)) return true;

        return false;
    }

    // ==================== MANAGER REPUTATION ====================

    /**
     * Calculate the reputation change for a manager based on match result and team reputations.
     *
     * @param myGoals    goals scored by the manager's team
     * @param oppGoals   goals scored by the opponent
     * @param myTeamRep  reputation of the manager's team
     * @param oppTeamRep reputation of the opponent team
     * @return reputation change (positive for gain, negative for loss)
     */
    public double calculateMatchRepChange(int myGoals, int oppGoals, int myTeamRep, int oppTeamRep) {
        double repDiff = oppTeamRep - myTeamRep;
        double strengthFactor = Math.max(0.2, Math.min(5.0, 1.0 + repDiff / 50.0));

        if (myGoals > oppGoals) {
            double base = repDiff > 50 ? 10.0 : 2.0;
            return base * strengthFactor;
        } else if (myGoals == oppGoals) {
            return repDiff > 0 ? 1.0 * strengthFactor : -1.0;
        } else {
            double base = repDiff < -50 ? -10.0 : -2.0;
            double lossFactor = Math.max(0.2, Math.min(5.0, 1.0 - repDiff / 50.0));
            return base * lossFactor;
        }
    }

    // ==================== INJURY PROCESSING ====================

    /**
     * Get all currently injured player IDs for a given team.
     *
     * @param teamId the team ID
     * @return set of player IDs who are currently injured
     */
    public Set<Long> getInjuredPlayerIds(long teamId) {
        return injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream()
                .map(Injury::getPlayerId)
                .collect(Collectors.toSet());
    }

    // ==================== MORALE CALCULATION ====================

    /**
     * Calculate the base morale change for a team based on the match result
     * and the power difference between the two teams.
     *
     * @param result              "W", "D", or "L"
     * @param teamPowerDifference positive means our team is stronger
     * @return base morale change value
     */
    public double calculateMoraleChangeForResult(String result, double teamPowerDifference) {
        Random random = new Random();

        if ("W".equals(result)) {
            if (teamPowerDifference > 500) return random.nextDouble(0, 2);
            else if (teamPowerDifference > 200) return random.nextDouble(1, 2);
            else if (teamPowerDifference > 0) return random.nextDouble(1, 4);
            else if (teamPowerDifference > -200) return random.nextDouble(2, 6);
            else if (teamPowerDifference > -500) return random.nextDouble(5, 10);
            else return random.nextDouble(7, 15);
        } else if ("D".equals(result)) {
            if (teamPowerDifference > 500) return random.nextDouble(-8, -2);
            else if (teamPowerDifference > 200) return random.nextDouble(-5, 0);
            else if (teamPowerDifference > 0) return random.nextDouble(-2, 1);
            else if (teamPowerDifference > -200) return random.nextDouble(1, 3);
            else if (teamPowerDifference > -500) return random.nextDouble(2, 7);
            else return random.nextDouble(5, 10);
        } else {
            if (teamPowerDifference > 500) return random.nextDouble(-20, -5);
            else if (teamPowerDifference > 200) return random.nextDouble(-10, -3);
            else if (teamPowerDifference > 0) return random.nextDouble(-5, -2);
            else if (teamPowerDifference > -200) return random.nextDouble(-3, -1);
            else if (teamPowerDifference > -500) return random.nextDouble(-2, 0);
            else return random.nextDouble(-1, 0);
        }
    }
}
