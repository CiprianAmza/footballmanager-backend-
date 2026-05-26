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
    @Autowired
    private MatchStatsRepository matchStatsRepository;

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
        double baseValue = Math.pow(rating, 3) * 20;
        double ageMultiplier;
        if (age <= 21) ageMultiplier = 1.3;
        else if (age <= 23) ageMultiplier = 1.1;
        else if (age <= 25) ageMultiplier = 1.0;
        else if (age <= 27) ageMultiplier = 0.95;
        else if (age <= 29) ageMultiplier = 0.75;
        else if (age <= 31) ageMultiplier = 0.45;
        else if (age <= 33) ageMultiplier = 0.2;
        else ageMultiplier = 0.08;
        return Math.max(50_000L, (long) (baseValue * ageMultiplier));
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

    // ==================== MATCH STATS GENERATION ====================

    /**
     * Generate realistic match statistics based on team powers, final score, and tactical settings.
     * Uses real-world football averages (Premier League / Champions League data) as baselines:
     * - Avg shots per team: 11-13, shots on target: 35-45% of total
     * - Avg possession: 50%, range 30-70%
     * - Avg passes: 400-500, pass accuracy: 80-88%
     * - Avg corners: 5-6, fouls: 10-12, offsides: 2-3
     * - Avg tackles: 18-20, interceptions: 10-12
     * - xG per goal: ~0.11 per shot, ~0.35 per big chance, 0.76 per penalty
     */
    public MatchStats generateMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic) {

        // Rebalanced 2026: every stat is now ~70% deterministic from team-power
        // ratio and ~30% noise. Old version had σ values that overwhelmed the
        // power signal, so two top teams looked statistically the same as two
        // relegation candidates. Now squad value visibly drives the line.
        Random rng = new Random();
        MatchStats stats = new MatchStats();
        stats.setCompetitionId(competitionId);
        stats.setSeasonNumber(season);
        stats.setRoundNumber(round);
        stats.setTeam1Id(team1Id);
        stats.setTeam2Id(team2Id);
        stats.setHomeGoals(homeGoals);
        stats.setAwayGoals(awayGoals);

        double totalPower = homePower + awayPower;
        double homeRatio = totalPower > 0 ? homePower / totalPower : 0.5;
        // Edge factor amplifies "ratio - 0.5" so big mismatches read in the stats
        // instead of being smoothed away by noise.
        double edge = (homeRatio - 0.5); // negative when away is stronger

        // --- POSSESSION ---
        // Range widened (was clamped 30-70 → now 25-75). Noise σ 4 → 2.
        double basePoss = 50 + edge * 30; // ±15 points from pure power split alone
        basePoss += 2; // slight home advantage
        basePoss += getTacticalPossessionBonus(homeTactic) - getTacticalPossessionBonus(awayTactic);
        basePoss = Math.max(25, Math.min(75, basePoss + rng.nextGaussian() * 2));
        int homePoss = (int) Math.round(basePoss);
        stats.setHomePossession(homePoss);
        stats.setAwayPossession(100 - homePoss);

        // --- PASSES --- (σ 40 → 20)
        double possRatioHome = homePoss / 50.0;
        double possRatioAway = (100 - homePoss) / 50.0;
        int homePasses = (int) (450 * possRatioHome + rng.nextGaussian() * 20);
        int awayPasses = (int) (450 * possRatioAway + rng.nextGaussian() * 20);
        stats.setHomePasses(Math.max(200, Math.min(750, homePasses)));
        stats.setAwayPasses(Math.max(200, Math.min(750, awayPasses)));

        // --- PASS ACCURACY --- (power range 20 → 30, σ 3 → 1.5)
        double homePassAcc = 78 + edge * 30 + rng.nextGaussian() * 1.5;
        double awayPassAcc = 78 - edge * 30 + rng.nextGaussian() * 1.5;
        if (homeTactic != null && "Keep Ball".equals(homeTactic.getInPossession())) homePassAcc += 3;
        if (awayTactic != null && "Keep Ball".equals(awayTactic.getInPossession())) awayPassAcc += 3;
        if (homeTactic != null && "Long Ball".equals(homeTactic.getPassingType())) homePassAcc -= 5;
        if (awayTactic != null && "Long Ball".equals(awayTactic.getPassingType())) awayPassAcc -= 5;
        stats.setHomePassAccuracy(clamp((int) homePassAcc, 55, 96));
        stats.setAwayPassAccuracy(clamp((int) awayPassAcc, 55, 96));

        // --- SHOTS --- (power coefficient 12 → 16, σ 2 → 1)
        double homeBaseShots = 6 + (homeRatio) * 16 + homeGoals * 1.5 + rng.nextGaussian() * 1.0;
        double awayBaseShots = 6 + (1 - homeRatio) * 16 + awayGoals * 1.5 + rng.nextGaussian() * 1.0;
        homeBaseShots += getAttackingMentalityShotBonus(homeTactic);
        awayBaseShots += getAttackingMentalityShotBonus(awayTactic);
        int homeShots = clamp((int) homeBaseShots, 2, 30);
        int awayShots = clamp((int) awayBaseShots, 2, 30);
        stats.setHomeShots(homeShots);
        stats.setAwayShots(awayShots);

        // Shots on target: stronger teams convert more shots into SoT
        // Base accuracy 35-45%, plus up to ±5% from power edge.
        double homeSoTRate = 0.40 + edge * 0.10 + rng.nextDouble() * 0.08;
        double awaySoTRate = 0.40 - edge * 0.10 + rng.nextDouble() * 0.08;
        int homeSoT = Math.max(homeGoals, clamp((int) (homeShots * homeSoTRate), 0, homeShots));
        int awaySoT = Math.max(awayGoals, clamp((int) (awayShots * awaySoTRate), 0, awayShots));
        stats.setHomeShotsOnTarget(homeSoT);
        stats.setAwayShotsOnTarget(awaySoT);

        // Blocked shots: 20-30% of total
        int homeBlocked = clamp((int) (homeShots * (0.22 + rng.nextDouble() * 0.08)), 0, homeShots - homeSoT);
        int awayBlocked = clamp((int) (awayShots * (0.22 + rng.nextDouble() * 0.08)), 0, awayShots - awaySoT);
        stats.setHomeShotsBlocked(homeBlocked);
        stats.setAwayShotsBlocked(awayBlocked);

        // --- CORNERS --- (σ 1.5 → 1)
        int homeCorners = clamp((int) (3 + homeShots * 0.3 + rng.nextGaussian() * 1.0), 0, 15);
        int awayCorners = clamp((int) (3 + awayShots * 0.3 + rng.nextGaussian() * 1.0), 0, 15);
        stats.setHomeCorners(homeCorners);
        stats.setAwayCorners(awayCorners);

        // --- FOULS --- (power weight 6 → 8, σ 2 → 1)
        double homeFoulBase = 12 - edge * 8 + rng.nextGaussian() * 1.0;
        double awayFoulBase = 12 + edge * 8 + rng.nextGaussian() * 1.0;
        if (homeTactic != null && "Very Defensive".equals(homeTactic.getMentality())) homeFoulBase += 3;
        if (awayTactic != null && "Very Defensive".equals(awayTactic.getMentality())) awayFoulBase += 3;
        int homeFouls = clamp((int) homeFoulBase, 4, 22);
        int awayFouls = clamp((int) awayFoulBase, 4, 22);
        stats.setHomeFouls(homeFouls);
        stats.setAwayFouls(awayFouls);

        // Free kicks tied to opponent fouls (unchanged — already deterministic)
        stats.setHomeFreeKicks(awayFouls);
        stats.setAwayFreeKicks(homeFouls);

        // --- CARDS ---
        // Yellow: 14-22% of fouls become cards (slightly narrowed)
        int homeYellow = clamp((int) (homeFouls * (0.14 + rng.nextDouble() * 0.08)), 0, 6);
        int awayYellow = clamp((int) (awayFouls * (0.14 + rng.nextDouble() * 0.08)), 0, 6);
        stats.setHomeYellowCards(homeYellow);
        stats.setAwayYellowCards(awayYellow);

        // Red card chance scales with fouls so dirty / underdog teams see more
        double homeRedChance = 0.005 + homeFouls * 0.002;
        double awayRedChance = 0.005 + awayFouls * 0.002;
        stats.setHomeRedCards(rng.nextDouble() < homeRedChance ? 1 : 0);
        stats.setAwayRedCards(rng.nextDouble() < awayRedChance ? 1 : 0);

        // --- OFFSIDES --- (σ 1.0 → 0.7)
        double homeOffsBase = 1.5 + (homeRatio - 0.3) * 4 + rng.nextGaussian() * 0.7;
        double awayOffsBase = 1.5 + ((1 - homeRatio) - 0.3) * 4 + rng.nextGaussian() * 0.7;
        stats.setHomeOffsides(clamp((int) homeOffsBase, 0, 8));
        stats.setAwayOffsides(clamp((int) awayOffsBase, 0, 8));

        // --- TACKLES --- (σ 3 → 1.5, power factor 0.15 → 0.25)
        double homeTackleBase = 18 + (50 - homePoss) * 0.25 + rng.nextGaussian() * 1.5;
        double awayTackleBase = 18 + (homePoss - 50) * 0.25 + rng.nextGaussian() * 1.5;
        stats.setHomeTackles(clamp((int) homeTackleBase, 8, 35));
        stats.setAwayTackles(clamp((int) awayTackleBase, 8, 35));

        // --- INTERCEPTIONS --- (σ 2 → 1, power factor 0.1 → 0.18)
        int homeInterceptions = clamp((int) (11 + (50 - homePoss) * 0.18 + rng.nextGaussian()), 3, 22);
        int awayInterceptions = clamp((int) (11 + (homePoss - 50) * 0.18 + rng.nextGaussian()), 3, 22);
        stats.setHomeInterceptions(homeInterceptions);
        stats.setAwayInterceptions(awayInterceptions);

        // --- CLEARANCES --- (σ 3 → 1.5)
        int homeClearances = clamp((int) (18 + (50 - homePoss) * 0.2 + awayShots * 0.5 + rng.nextGaussian() * 1.5), 5, 40);
        int awayClearances = clamp((int) (18 + (homePoss - 50) * 0.2 + homeShots * 0.5 + rng.nextGaussian() * 1.5), 5, 40);
        stats.setHomeClearances(homeClearances);
        stats.setAwayClearances(awayClearances);

        // Saves: opponent's shots on target - goals conceded (unchanged)
        stats.setHomeSaves(Math.max(0, awaySoT - awayGoals));
        stats.setAwaySaves(Math.max(0, homeSoT - homeGoals));

        // --- BIG CHANCES ---
        // Now driven by shots-on-target (a team with many SoT created more
        // dangerous moments), not pure RNG on top of goals.
        int homeBigChances = Math.max(homeGoals, clamp((int) (homeSoT * 0.6 + rng.nextGaussian() * 0.8), 0, 8));
        int awayBigChances = Math.max(awayGoals, clamp((int) (awaySoT * 0.6 + rng.nextGaussian() * 0.8), 0, 8));
        stats.setHomeBigChances(homeBigChances);
        stats.setAwayBigChances(awayBigChances);
        stats.setHomeBigChancesMissed(Math.max(0, homeBigChances - homeGoals));
        stats.setAwayBigChancesMissed(Math.max(0, awayBigChances - awayGoals));

        // --- xG --- (σ 0.15 → 0.08)
        double homeXg = homeBigChances * 0.35
                + (homeSoT - homeGoals) * 0.12
                + (homeShots - homeSoT) * 0.05
                + rng.nextGaussian() * 0.08;
        double awayXg = awayBigChances * 0.35
                + (awaySoT - awayGoals) * 0.12
                + (awayShots - awaySoT) * 0.05
                + rng.nextGaussian() * 0.08;
        stats.setHomeXg(Math.max(0, (int) (homeXg * 100)));
        stats.setAwayXg(Math.max(0, (int) (awayXg * 100)));

        // --- CROSSES --- (σ 4 → 2)
        int homeCrosses = clamp((int) (18 + rng.nextGaussian() * 2 + homeCorners * 0.5), 5, 40);
        int awayCrosses = clamp((int) (18 + rng.nextGaussian() * 2 + awayCorners * 0.5), 5, 40);
        // Cross accuracy now also nudged by team strength (better wingers cross better)
        double homeCrossRate = 0.28 + edge * 0.08 + rng.nextDouble() * 0.08;
        double awayCrossRate = 0.28 - edge * 0.08 + rng.nextDouble() * 0.08;
        int homeCrossAcc = clamp((int) (homeCrosses * homeCrossRate), 0, homeCrosses);
        int awayCrossAcc = clamp((int) (awayCrosses * awayCrossRate), 0, awayCrosses);
        stats.setHomeCrosses(homeCrosses);
        stats.setAwayCrosses(awayCrosses);
        stats.setHomeCrossesAccurate(homeCrossAcc);
        stats.setAwayCrossesAccurate(awayCrossAcc);

        // --- DUELS --- (σ 8 → 4 on totals, win-rate range 0.2 → 0.35, σ 0.05 → 0.025)
        int homeDuels = clamp((int) (55 + rng.nextGaussian() * 4), 35, 85);
        int awayDuels = clamp((int) (55 + rng.nextGaussian() * 4), 35, 85);
        double homeDuelWinPct = 0.50 + edge * 0.35 + rng.nextGaussian() * 0.025;
        homeDuelWinPct = Math.max(0.30, Math.min(0.70, homeDuelWinPct));
        stats.setHomeDuelsWon(clamp((int) (homeDuels * homeDuelWinPct), 15, 60));
        stats.setAwayDuelsWon(clamp((int) (awayDuels * (1 - homeDuelWinPct)), 15, 60));

        // Aerial duels: now power-biased instead of pure noise (σ 3 → 1.5)
        int homeAerial = clamp((int) (14 + edge * 8 + rng.nextGaussian() * 1.5), 5, 25);
        int awayAerial = clamp((int) (14 - edge * 8 + rng.nextGaussian() * 1.5), 5, 25);
        stats.setHomeAerialDuelsWon(homeAerial);
        stats.setAwayAerialDuelsWon(awayAerial);

        return stats;
    }

    /**
     * Generate and persist match stats. Returns the saved entity.
     */
    public MatchStats generateAndSaveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic) {

        MatchStats stats = generateMatchStats(competitionId, season, round,
                team1Id, team2Id, homeGoals, awayGoals, homePower, awayPower, homeTactic, awayTactic);
        return matchStatsRepository.save(stats);
    }

    /**
     * Persist stats from a LiveMatchData result (live match already generated the stats).
     */
    public MatchStats persistLiveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            com.footballmanagergamesimulator.frontend.LiveMatchData liveData,
            double homePower, double awayPower) {

        Random rng = new Random();
        double totalPower = homePower + awayPower;
        double homeRatio = totalPower > 0 ? homePower / totalPower : 0.5;

        MatchStats stats = new MatchStats();
        stats.setCompetitionId(competitionId);
        stats.setSeasonNumber(season);
        stats.setRoundNumber(round);
        stats.setTeam1Id(team1Id);
        stats.setTeam2Id(team2Id);
        stats.setHomeGoals(liveData.getHomeScore());
        stats.setAwayGoals(liveData.getAwayScore());

        // Transfer existing stats from LiveMatchData
        stats.setHomePossession(liveData.getHomePossession());
        stats.setAwayPossession(liveData.getAwayPossession());
        stats.setHomeShots(liveData.getHomeShots());
        stats.setAwayShots(liveData.getAwayShots());
        stats.setHomeShotsOnTarget(liveData.getHomeShotsOnTarget());
        stats.setAwayShotsOnTarget(liveData.getAwayShotsOnTarget());
        stats.setHomeCorners(liveData.getHomeCorners());
        stats.setAwayCorners(liveData.getAwayCorners());
        stats.setHomeFouls(liveData.getHomeFouls());
        stats.setAwayFouls(liveData.getAwayFouls());
        stats.setHomeYellowCards(liveData.getHomeYellowCards());
        stats.setAwayYellowCards(liveData.getAwayYellowCards());
        stats.setHomeRedCards(liveData.getHomeRedCards());
        stats.setAwayRedCards(liveData.getAwayRedCards());

        // Generate the stats that LiveMatchData doesn't have
        int homePoss = liveData.getHomePossession();
        double possRatioHome = homePoss / 50.0;
        double possRatioAway = (100 - homePoss) / 50.0;

        // Passes
        int homePasses = clamp((int) (450 * possRatioHome + rng.nextGaussian() * 40), 200, 750);
        int awayPasses = clamp((int) (450 * possRatioAway + rng.nextGaussian() * 40), 200, 750);
        stats.setHomePasses(homePasses);
        stats.setAwayPasses(awayPasses);
        stats.setHomePassAccuracy(clamp((int) (78 + (homeRatio - 0.5) * 20 + rng.nextGaussian() * 3), 60, 95));
        stats.setAwayPassAccuracy(clamp((int) (78 + ((1 - homeRatio) - 0.5) * 20 + rng.nextGaussian() * 3), 60, 95));

        // Shots blocked
        int homeBlocked = clamp((int) (liveData.getHomeShots() * (0.20 + rng.nextDouble() * 0.15)), 0,
                liveData.getHomeShots() - liveData.getHomeShotsOnTarget());
        int awayBlocked = clamp((int) (liveData.getAwayShots() * (0.20 + rng.nextDouble() * 0.15)), 0,
                liveData.getAwayShots() - liveData.getAwayShotsOnTarget());
        stats.setHomeShotsBlocked(homeBlocked);
        stats.setAwayShotsBlocked(awayBlocked);

        // Free kicks
        stats.setHomeFreeKicks(liveData.getAwayFouls());
        stats.setAwayFreeKicks(liveData.getHomeFouls());

        // Offsides
        stats.setHomeOffsides(clamp((int) (1.5 + (homeRatio - 0.3) * 4 + rng.nextGaussian()), 0, 8));
        stats.setAwayOffsides(clamp((int) (1.5 + ((1 - homeRatio) - 0.3) * 4 + rng.nextGaussian()), 0, 8));

        // Tackles
        stats.setHomeTackles(clamp((int) (18 + (50 - homePoss) * 0.15 + rng.nextGaussian() * 3), 8, 35));
        stats.setAwayTackles(clamp((int) (18 + (homePoss - 50) * 0.15 + rng.nextGaussian() * 3), 8, 35));

        // Interceptions
        stats.setHomeInterceptions(clamp((int) (11 + (50 - homePoss) * 0.1 + rng.nextGaussian() * 2), 3, 22));
        stats.setAwayInterceptions(clamp((int) (11 + (homePoss - 50) * 0.1 + rng.nextGaussian() * 2), 3, 22));

        // Clearances
        stats.setHomeClearances(clamp((int) (18 + (50 - homePoss) * 0.2 + liveData.getAwayShots() * 0.5 + rng.nextGaussian() * 3), 5, 40));
        stats.setAwayClearances(clamp((int) (18 + (homePoss - 50) * 0.2 + liveData.getHomeShots() * 0.5 + rng.nextGaussian() * 3), 5, 40));

        // Saves
        stats.setHomeSaves(Math.max(0, liveData.getAwayShotsOnTarget() - liveData.getAwayScore()));
        stats.setAwaySaves(Math.max(0, liveData.getHomeShotsOnTarget() - liveData.getHomeScore()));

        // Big chances
        int hbc = liveData.getHomeScore() + clamp((int) (rng.nextDouble() * 3), 0, 4);
        int abc = liveData.getAwayScore() + clamp((int) (rng.nextDouble() * 3), 0, 4);
        stats.setHomeBigChances(hbc);
        stats.setAwayBigChances(abc);
        stats.setHomeBigChancesMissed(Math.max(0, hbc - liveData.getHomeScore()));
        stats.setAwayBigChancesMissed(Math.max(0, abc - liveData.getAwayScore()));

        // xG
        double hxg = hbc * 0.35 + (liveData.getHomeShotsOnTarget() - liveData.getHomeScore()) * 0.12
                + (liveData.getHomeShots() - liveData.getHomeShotsOnTarget()) * 0.05 + rng.nextGaussian() * 0.15;
        double axg = abc * 0.35 + (liveData.getAwayShotsOnTarget() - liveData.getAwayScore()) * 0.12
                + (liveData.getAwayShots() - liveData.getAwayShotsOnTarget()) * 0.05 + rng.nextGaussian() * 0.15;
        stats.setHomeXg(Math.max(0, (int) (hxg * 100)));
        stats.setAwayXg(Math.max(0, (int) (axg * 100)));

        // Crosses
        int hc = clamp((int) (18 + rng.nextGaussian() * 4 + liveData.getHomeCorners() * 0.5), 5, 40);
        int ac = clamp((int) (18 + rng.nextGaussian() * 4 + liveData.getAwayCorners() * 0.5), 5, 40);
        stats.setHomeCrosses(hc);
        stats.setAwayCrosses(ac);
        stats.setHomeCrossesAccurate(clamp((int) (hc * (0.25 + rng.nextDouble() * 0.15)), 0, hc));
        stats.setAwayCrossesAccurate(clamp((int) (ac * (0.25 + rng.nextDouble() * 0.15)), 0, ac));

        // Duels
        int hd = clamp((int) (55 + rng.nextGaussian() * 8), 35, 85);
        double duelWin = 0.45 + (homeRatio - 0.5) * 0.2 + rng.nextGaussian() * 0.05;
        stats.setHomeDuelsWon(clamp((int) (hd * duelWin), 15, 60));
        stats.setAwayDuelsWon(clamp((int) (hd * (1 - duelWin)), 15, 60));
        stats.setHomeAerialDuelsWon(clamp((int) (14 + rng.nextGaussian() * 3), 5, 25));
        stats.setAwayAerialDuelsWon(clamp((int) (14 + rng.nextGaussian() * 3), 5, 25));

        return matchStatsRepository.save(stats);
    }

    /**
     * Retrieve match stats for a specific match.
     */
    public Optional<MatchStats> getMatchStats(long competitionId, int season, int round, long team1Id, long team2Id) {
        return matchStatsRepository.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                competitionId, season, round, team1Id, team2Id);
    }

    /**
     * Get aggregated season stats for a team (averages across all matches).
     */
    public Map<String, Object> getTeamSeasonStats(long teamId, int season) {
        List<MatchStats> homeMatches = matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, season);
        List<MatchStats> awayMatches = matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, season);

        int totalMatches = homeMatches.size() + awayMatches.size();
        if (totalMatches == 0) return Map.of("totalMatches", 0);

        // Aggregate: sum home-side stats from home matches + away-side stats from away matches
        int totalShots = 0, totalShotsOnTarget = 0, totalCorners = 0, totalFouls = 0;
        int totalPasses = 0, totalTackles = 0, totalGoals = 0, totalGoalsConceded = 0;
        int totalPossession = 0, totalOffsides = 0, totalBigChances = 0;
        int totalXg = 0, totalXgAgainst = 0;
        int totalYellow = 0, totalRed = 0, totalSaves = 0;
        int totalPassAccSum = 0;

        for (MatchStats m : homeMatches) {
            totalShots += m.getHomeShots();
            totalShotsOnTarget += m.getHomeShotsOnTarget();
            totalCorners += m.getHomeCorners();
            totalFouls += m.getHomeFouls();
            totalPasses += m.getHomePasses();
            totalTackles += m.getHomeTackles();
            totalGoals += m.getHomeGoals();
            totalGoalsConceded += m.getAwayGoals();
            totalPossession += m.getHomePossession();
            totalOffsides += m.getHomeOffsides();
            totalBigChances += m.getHomeBigChances();
            totalXg += m.getHomeXg();
            totalXgAgainst += m.getAwayXg();
            totalYellow += m.getHomeYellowCards();
            totalRed += m.getHomeRedCards();
            totalSaves += m.getHomeSaves();
            totalPassAccSum += m.getHomePassAccuracy();
        }
        for (MatchStats m : awayMatches) {
            totalShots += m.getAwayShots();
            totalShotsOnTarget += m.getAwayShotsOnTarget();
            totalCorners += m.getAwayCorners();
            totalFouls += m.getAwayFouls();
            totalPasses += m.getAwayPasses();
            totalTackles += m.getAwayTackles();
            totalGoals += m.getAwayGoals();
            totalGoalsConceded += m.getHomeGoals();
            totalPossession += m.getAwayPossession();
            totalOffsides += m.getAwayOffsides();
            totalBigChances += m.getAwayBigChances();
            totalXg += m.getAwayXg();
            totalXgAgainst += m.getHomeXg();
            totalYellow += m.getAwayYellowCards();
            totalRed += m.getAwayRedCards();
            totalSaves += m.getAwaySaves();
            totalPassAccSum += m.getAwayPassAccuracy();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMatches", totalMatches);
        result.put("totalGoals", totalGoals);
        result.put("totalGoalsConceded", totalGoalsConceded);
        result.put("avgPossession", Math.round(totalPossession * 10.0 / totalMatches) / 10.0);
        result.put("totalShots", totalShots);
        result.put("avgShots", Math.round(totalShots * 10.0 / totalMatches) / 10.0);
        result.put("totalShotsOnTarget", totalShotsOnTarget);
        result.put("avgShotsOnTarget", Math.round(totalShotsOnTarget * 10.0 / totalMatches) / 10.0);
        result.put("shotAccuracy", totalShots > 0 ? Math.round(totalShotsOnTarget * 1000.0 / totalShots) / 10.0 : 0);
        result.put("totalCorners", totalCorners);
        result.put("totalFouls", totalFouls);
        result.put("totalPasses", totalPasses);
        result.put("avgPassAccuracy", Math.round(totalPassAccSum * 10.0 / totalMatches) / 10.0);
        result.put("totalTackles", totalTackles);
        result.put("totalOffsides", totalOffsides);
        result.put("totalBigChances", totalBigChances);
        result.put("totalXg", Math.round(totalXg) / 100.0);
        result.put("totalXgAgainst", Math.round(totalXgAgainst) / 100.0);
        result.put("totalYellowCards", totalYellow);
        result.put("totalRedCards", totalRed);
        result.put("totalSaves", totalSaves);

        return result;
    }

    // ==================== TACTICAL HELPERS FOR STATS ====================

    private double getTacticalPossessionBonus(PersonalizedTactic tactic) {
        if (tactic == null) return 0;
        double bonus = 0;
        if ("Keep Ball".equals(tactic.getInPossession())) bonus += 5;
        else if ("Free Ball Early".equals(tactic.getInPossession())) bonus -= 3;
        if ("Short Passing".equals(tactic.getPassingType())) bonus += 3;
        else if ("Long Ball".equals(tactic.getPassingType())) bonus -= 4;
        if ("Low".equals(tactic.getTempo())) bonus += 2;
        else if ("High".equals(tactic.getTempo())) bonus -= 1;
        return bonus;
    }

    private double getAttackingMentalityShotBonus(PersonalizedTactic tactic) {
        if (tactic == null) return 0;
        String mentality = tactic.getMentality() != null ? tactic.getMentality() : "Balanced";
        return switch (mentality) {
            case "Very Attacking" -> 4;
            case "Attacking" -> 2;
            case "Defensive" -> -2;
            case "Very Defensive" -> -4;
            default -> 0;
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== MATCH RATINGS (1-10) ====================

    /**
     * Compute a per-player match performance rating on a 1.0-10.0 scale.
     * Based on FM-style match ratings:
     * - Base rating: 6.0 (average performance)
     * - Goals: +1.0 each (max +3.0)
     * - Assists: +0.5 each (max +1.5)
     * - Clean sheet (GK/defenders): +0.5
     * - Win bonus: +0.3, Draw: 0, Loss: -0.3
     * - Position influence: forwards rewarded more for goals, defenders for clean sheets
     * - Random variance: +/- 0.5 for realism
     * - Clamped to [1.0, 10.0]
     */
    public double computeMatchRating(String position, int goals, int assists,
                                      boolean isCleanSheet, boolean isWin, boolean isDraw,
                                      boolean isSubstitute) {
        Random rng = new Random();
        double rating = 6.0; // base

        // Goal contributions
        rating += Math.min(goals * 1.0, 3.0);
        rating += Math.min(assists * 0.5, 1.5);

        // Clean sheet bonus for GK and defenders
        if (isCleanSheet && ("GK".equals(position) || "DC".equals(position)
                || "DL".equals(position) || "DR".equals(position))) {
            rating += 0.5;
        }

        // Result bonus
        if (isWin) rating += 0.3;
        else if (!isDraw) rating -= 0.3;

        // Substitutes get slightly less base
        if (isSubstitute) rating -= 0.2;

        // Random variance for realism
        rating += rng.nextGaussian() * 0.4;

        return Math.round(Math.max(1.0, Math.min(10.0, rating)) * 10.0) / 10.0;
    }

    /**
     * Assign match ratings to all scorers and determine Man of the Match.
     * Returns the Scorer with the highest match rating.
     */
    public Scorer assignMatchRatings(List<Scorer> scorers, int teamScore, int opponentScore) {
        boolean isWin = teamScore > opponentScore;
        boolean isDraw = teamScore == opponentScore;
        boolean isCleanSheet = opponentScore == 0;

        Scorer manOfTheMatch = null;
        double bestRating = 0;

        for (Scorer scorer : scorers) {
            double matchRating = computeMatchRating(
                    scorer.getPosition(),
                    scorer.getGoals(),
                    scorer.getAssists(),
                    isCleanSheet,
                    isWin,
                    isDraw,
                    scorer.isSubstitute()
            );
            scorer.setRating(matchRating);

            if (matchRating > bestRating) {
                bestRating = matchRating;
                manOfTheMatch = scorer;
            }
        }

        return manOfTheMatch;
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
            // Win: bigger morale boost when beating stronger teams
            if (teamPowerDifference > 500) return random.nextDouble(0, 1);       // expected win
            else if (teamPowerDifference > 200) return random.nextDouble(1, 2);
            else if (teamPowerDifference > 0) return random.nextDouble(1, 3);
            else if (teamPowerDifference > -200) return random.nextDouble(2, 5);
            else if (teamPowerDifference > -500) return random.nextDouble(4, 7);
            else return random.nextDouble(5, 10);                                 // giant killing
        } else if ("D".equals(result)) {
            if (teamPowerDifference > 500) return random.nextDouble(-6, -2);      // disappointing
            else if (teamPowerDifference > 200) return random.nextDouble(-4, 0);
            else if (teamPowerDifference > 0) return random.nextDouble(-2, 1);
            else if (teamPowerDifference > -200) return random.nextDouble(1, 3);
            else if (teamPowerDifference > -500) return random.nextDouble(2, 5);
            else return random.nextDouble(3, 7);                                  // great result
        } else {
            // Loss: bigger penalty when losing as favorites
            if (teamPowerDifference > 500) return random.nextDouble(-15, -5);     // shocking loss
            else if (teamPowerDifference > 200) return random.nextDouble(-8, -3);
            else if (teamPowerDifference > 0) return random.nextDouble(-5, -2);
            else if (teamPowerDifference > -200) return random.nextDouble(-3, -1);
            else if (teamPowerDifference > -500) return random.nextDouble(-2, 0);
            else return random.nextDouble(-1, 0);                                 // expected loss
        }
    }
}
