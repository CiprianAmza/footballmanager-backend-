package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core match simulation utilities (scores themselves come from Compartment V1): match events
 * timeline, player availability, manager-reputation deltas, per-player match
 * ratings, and morale changes.
 *
 * <p>Stats generation/persistence/aggregation lives in {@link MatchStatsService}
 * after the §6.4 split; transfer value lookup lives in {@link TransferValueCalculator}.
 */
@Service
public class MatchSimulationService {

    @Autowired private HumanRepository humanRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private com.footballmanagergamesimulator.repository.PlayerSkillsRepository playerSkillsRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private SuspensionRepository suspensionRepository;
    @Autowired private UserContext userContext;
    @Autowired MatchEngineConfig engineConfig; // package-private so unit tests can inject a plain config
    @Autowired(required = false) GameplayFeatureConfig gameplayFeatures;

    /**
     * Shared RNG for match ratings and event timelines. A field so tests can swap in a seeded
     * {@link Random} via {@link #setRandomForTesting(Random)}; production keeps the default.
     */
    private Random random = new Random();

    /** Test-only seam: swap the RNG for a seeded one. Production code must not call this. */
    public void setRandomForTesting(Random random) {
        this.random = random;
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
        if (availabilityDisabled()) return false;
        Optional<Injury> activeInjury = injuryRepository.findByPlayerIdAndDaysRemainingGreaterThan(playerId, 0);
        return activeInjury.isPresent();
    }

    /**
     * Find all active suspensions for a given player.
     * Kept repository-only to avoid coupling this low-level match utility to the
     * notification/discipline service.
     */
    private List<Suspension> findActiveSuspensionsForPlayer(long playerId) {
        if (availabilityDisabled()) return List.of();
        return suspensionRepository.findAllByPlayerIdAndActive(playerId, true);
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
        List<Scorer> team1Scorers = scorerRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamIdAndOpponentTeamId(
                        competitionId, season, roundNumber, team1Id, team2Id);
        List<Scorer> team2Scorers = scorerRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamIdAndOpponentTeamId(
                        competitionId, season, roundNumber, team2Id, team1Id);

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
        MatchEngineConfig.Reputation rep = engineConfig.getReputation();
        double repDiff = oppTeamRep - myTeamRep;
        double divisor = rep.getStrengthFactorDivisor();
        double sfMin = rep.getStrengthFactorMin();
        double sfMax = rep.getStrengthFactorMax();
        double strengthFactor = Math.max(sfMin, Math.min(sfMax, 1.0 + repDiff / divisor));
        double shockThreshold = rep.getShockingThreshold();

        if (myGoals > oppGoals) {
            double base = repDiff > shockThreshold ? rep.getWinShockingBase() : rep.getWinExpectedBase();
            return base * strengthFactor;
        } else if (myGoals == oppGoals) {
            return repDiff > 0 ? rep.getDrawFavoredOpp() * strengthFactor : rep.getDrawDisfavoredOpp();
        } else {
            double base = repDiff < -shockThreshold ? rep.getLossShockingBase() : rep.getLossExpectedBase();
            double lossFactor = Math.max(sfMin, Math.min(sfMax, 1.0 - repDiff / divisor));
            return base * lossFactor;
        }
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
        MatchEngineConfig.Ratings r = engineConfig.getRatings();

        // A hat-trick is the defining individual performance of the match. It must not be
        // dragged below 10 by the normal result/substitute modifiers or by random variance.
        // This also covers four-or-more-goal performances.
        if (goals >= 3) {
            return r.getMax();
        }

        // Uses field-level RNG so determinism IT (seed → reproducible output) holds.
        double rating = r.getBase();

        rating += Math.min(goals * r.getPerGoal(), r.getGoalContributionMax());
        rating += Math.min(assists * r.getPerAssist(), r.getAssistContributionMax());

        if (isCleanSheet && ("GK".equals(position) || "DC".equals(position)
                || "DL".equals(position) || "DR".equals(position))) {
            rating += r.getCleanSheetBonus();
        }

        if (isWin) rating += r.getWinBonus();
        else if (!isDraw) rating += r.getLossPenalty();

        if (isSubstitute) rating += r.getSubstitutePenalty();

        rating += this.random.nextGaussian() * r.getVarianceSigma();

        return Math.round(Math.max(r.getMin(), Math.min(r.getMax(), rating)) * 10.0) / 10.0;
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
        if (availabilityDisabled()) return Set.of();
        return injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream()
                .map(Injury::getPlayerId)
                .collect(Collectors.toSet());
    }

    private boolean availabilityDisabled() {
        return gameplayFeatures != null && gameplayFeatures.isPlayerAvailabilityDisabled();
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
        MatchEngineConfig.Morale m = engineConfig.getMorale();
        // Uses field-level RNG so determinism IT holds across morale-aware tests.
        Random random = this.random;
        double bigDiff = m.getTierBigDiff();
        double midDiff = m.getTierMidDiff();
        double smallDiff = m.getTierSmallDiff();

        if ("W".equals(result)) {
            // Win: bigger morale boost when beating stronger teams
            if (teamPowerDifference > bigDiff)        return rangeSample(random, m.getWinFavoriteBig());
            else if (teamPowerDifference > midDiff)   return rangeSample(random, m.getWinFavorite());
            else if (teamPowerDifference > smallDiff) return rangeSample(random, m.getWinSlightFavorite());
            else if (teamPowerDifference > -midDiff)  return rangeSample(random, m.getWinSlightUnderdog());
            else if (teamPowerDifference > -bigDiff)  return rangeSample(random, m.getWinUnderdog());
            else                                       return rangeSample(random, m.getWinGiantKilling());
        } else if ("D".equals(result)) {
            if (teamPowerDifference > bigDiff)        return rangeSample(random, m.getDrawFavoriteBig());
            else if (teamPowerDifference > midDiff)   return rangeSample(random, m.getDrawFavorite());
            else if (teamPowerDifference > smallDiff) return rangeSample(random, m.getDrawSlightFavorite());
            else if (teamPowerDifference > -midDiff)  return rangeSample(random, m.getDrawSlightUnderdog());
            else if (teamPowerDifference > -bigDiff)  return rangeSample(random, m.getDrawUnderdog());
            else                                       return rangeSample(random, m.getDrawGiantHold());
        } else {
            // Loss: bigger penalty when losing as favorites
            if (teamPowerDifference > bigDiff)        return rangeSample(random, m.getLossFavoriteBig());
            else if (teamPowerDifference > midDiff)   return rangeSample(random, m.getLossFavorite());
            else if (teamPowerDifference > smallDiff) return rangeSample(random, m.getLossSlightFavorite());
            else if (teamPowerDifference > -midDiff)  return rangeSample(random, m.getLossSlightUnderdog());
            else if (teamPowerDifference > -bigDiff)  return rangeSample(random, m.getLossUnderdog());
            else                                       return rangeSample(random, m.getLossExpected());
        }
    }

    private static double rangeSample(Random rng, MatchEngineConfig.SwingRange range) {
        return rng.nextDouble(range.getMin(), range.getMax());
    }

    // ==================== MATCH EVENTS GENERATION ====================

    /**
     * Synthesize and persist a timeline of {@link MatchEvent} rows from a final
     * score. Goal minutes are drawn uniformly across 1..90 and split between
     * the two teams in score-proportional shares; scorers come from the
     * outfield roster (penalty/free-kick takers honoured via the personalized
     * tactic when set). Yellow cards, the occasional red, and three subs per
     * side are appended only for human-team matches so AI vs AI games stay
     * cheap.
     */
    public List<MatchEvent> buildGoalAndAssistEvents(long competitionId, int seasonNumber, int roundNumber,
                                                     long teamId1, long teamId2, int teamScore1, int teamScore2,
                                                     String tactic1, String tactic2) {
        Random random = this.random;
        List<MatchEvent> events = new ArrayList<>();

        String[] goalDescriptions = {"Tap in", "Header", "Long range shot", "Free kick", "Penalty", "Solo run", "Volley"};
        Optional<PersonalizedTactic> tactic1Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
        Optional<PersonalizedTactic> tactic2Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);
        MatchEngineConfig.Events ev = engineConfig.getEvents();

        int totalGoals = teamScore1 + teamScore2;
        List<Integer> goalMinutes = new ArrayList<>();
        for (int i = 0; i < totalGoals; i++) {
            goalMinutes.add(random.nextInt(ev.getGoalMinuteMin(), ev.getGoalMinuteMax()));
        }
        Collections.sort(goalMinutes);

        List<Integer> team1GoalMinutes = new ArrayList<>();
        List<Integer> team2GoalMinutes = new ArrayList<>();
        List<Integer> shuffledIndices = new ArrayList<>();
        for (int i = 0; i < totalGoals; i++) shuffledIndices.add(i);
        Collections.shuffle(shuffledIndices);

        for (int i = 0; i < totalGoals; i++) {
            if (i < teamScore1) team1GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
            else team2GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
        }
        Collections.sort(team1GoalMinutes);
        Collections.sort(team2GoalMinutes);

        List<Human> team1Players = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.PLAYER_TYPE);
        List<Human> team2Players = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.PLAYER_TYPE);
        List<Human> team1Outfield = team1Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());
        List<Human> team2Outfield = team2Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());
        if (team1Outfield.isEmpty()) team1Outfield = new ArrayList<>(team1Players);
        if (team2Outfield.isEmpty()) team2Outfield = new ArrayList<>(team2Players);

        // Batch-load skills once so assist weighting can factor Passing/Vision.
        List<Long> allPlayerIds = new ArrayList<>();
        for (Human p : team1Outfield) allPlayerIds.add(p.getId());
        for (Human p : team2Outfield) allPlayerIds.add(p.getId());
        Map<Long, com.footballmanagergamesimulator.model.PlayerSkills> skillsCache =
                playerSkillsRepository.findAllByPlayerIdIn(allPlayerIds).stream()
                        .collect(Collectors.toMap(
                                com.footballmanagergamesimulator.model.PlayerSkills::getPlayerId,
                                ps -> ps, (a, b) -> a));

        appendGoalEvents(events, competitionId, seasonNumber, roundNumber, teamId1, teamId2, teamId1,
                team1GoalMinutes, team1Outfield, tactic1Opt.orElse(null), goalDescriptions, ev, skillsCache, random);
        appendGoalEvents(events, competitionId, seasonNumber, roundNumber, teamId1, teamId2, teamId2,
                team2GoalMinutes, team2Outfield, tactic2Opt.orElse(null), goalDescriptions, ev, skillsCache, random);
        return events;
    }

    public void generateMatchEvents(long competitionId, int seasonNumber, int roundNumber,
                                    long teamId1, long teamId2, int teamScore1, int teamScore2,
                                    String tactic1, String tactic2) {

        // Field-level RNG so determinism IT holds.
        Random random = this.random;
        List<MatchEvent> events = buildGoalAndAssistEvents(
                competitionId, seasonNumber, roundNumber, teamId1, teamId2, teamScore1, teamScore2, tactic1, tactic2);

        boolean isHumanMatch = userContext.isHumanTeam(teamId1) || userContext.isHumanTeam(teamId2);
        MatchEngineConfig.Events ev = engineConfig.getEvents();
        MatchEngineConfig.Fouls fl = engineConfig.getFouls();
        List<Human> team1Players = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.PLAYER_TYPE);
        List<Human> team2Players = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.PLAYER_TYPE);

        // Cards + subs only for human-team matches.
        if (isHumanMatch) {

            // Yellow cards: 0..(syntheticMaxYellowCardsPerTeam-1) per team
            for (long teamId : new long[]{teamId1, teamId2}) {
                List<Human> teamPlayers = (teamId == teamId1) ? team1Players : team2Players;
                if (teamPlayers.isEmpty()) continue;
                int yellowCards = random.nextInt(fl.getSyntheticMaxYellowCardsPerTeam());
                List<Human> shuffledPlayers = new ArrayList<>(teamPlayers);
                Collections.shuffle(shuffledPlayers);
                for (int i = 0; i < Math.min(yellowCards, shuffledPlayers.size()); i++) {
                    MatchEvent cardEvent = new MatchEvent();
                    cardEvent.setCompetitionId(competitionId);
                    cardEvent.setSeasonNumber(seasonNumber);
                    cardEvent.setRoundNumber(roundNumber);
                    cardEvent.setTeamId1(teamId1);
                    cardEvent.setTeamId2(teamId2);
                    cardEvent.setMinute(random.nextInt(ev.getYellowCardMinuteMin(), ev.getYellowCardMinuteMax()));
                    cardEvent.setEventType("yellow_card");
                    cardEvent.setPlayerId(shuffledPlayers.get(i).getId());
                    cardEvent.setPlayerName(shuffledPlayers.get(i).getName());
                    cardEvent.setTeamId(teamId);
                    cardEvent.setDetails("Yellow card");
                    events.add(cardEvent);
                }
            }

            // Red card chance per match
            if (random.nextDouble() < fl.getSyntheticRedCardChance()) {
                long redCardTeamId = random.nextBoolean() ? teamId1 : teamId2;
                List<Human> redCardPlayers = (redCardTeamId == teamId1) ? team1Players : team2Players;
                if (!redCardPlayers.isEmpty()) {
                    Human redCardPlayer = redCardPlayers.get(random.nextInt(redCardPlayers.size()));
                    MatchEvent redEvent = new MatchEvent();
                    redEvent.setCompetitionId(competitionId);
                    redEvent.setSeasonNumber(seasonNumber);
                    redEvent.setRoundNumber(roundNumber);
                    redEvent.setTeamId1(teamId1);
                    redEvent.setTeamId2(teamId2);
                    redEvent.setMinute(random.nextInt(fl.getSyntheticRedCardMinMinute(), fl.getSyntheticRedCardMaxMinute()));
                    redEvent.setEventType("red_card");
                    redEvent.setPlayerId(redCardPlayer.getId());
                    redEvent.setPlayerName(redCardPlayer.getName());
                    redEvent.setTeamId(redCardTeamId);
                    redEvent.setDetails("Red card");
                    events.add(redEvent);
                }
            }

            // Substitutions per team (configurable count + minute range)
            int subsPerTeam = ev.getSubstitutionsPerTeam();
            for (long teamId : new long[]{teamId1, teamId2}) {
                List<Human> teamPlayers = (teamId == teamId1) ? team1Players : team2Players;
                if (teamPlayers.size() < subsPerTeam + 1) continue;
                List<Human> shuffledPlayers = new ArrayList<>(teamPlayers);
                Collections.shuffle(shuffledPlayers);
                List<Integer> subMinutes = new ArrayList<>();
                for (int i = 0; i < subsPerTeam; i++) {
                    subMinutes.add(random.nextInt(ev.getSubstitutionMinuteMin(), ev.getSubstitutionMinuteMax()));
                }
                Collections.sort(subMinutes);
                for (int i = 0; i < subsPerTeam; i++) {
                    MatchEvent subEvent = new MatchEvent();
                    subEvent.setCompetitionId(competitionId);
                    subEvent.setSeasonNumber(seasonNumber);
                    subEvent.setRoundNumber(roundNumber);
                    subEvent.setTeamId1(teamId1);
                    subEvent.setTeamId2(teamId2);
                    subEvent.setMinute(subMinutes.get(i));
                    subEvent.setEventType("substitution");
                    subEvent.setPlayerId(shuffledPlayers.get(i).getId());
                    subEvent.setPlayerName(shuffledPlayers.get(i).getName());
                    subEvent.setTeamId(teamId);
                    subEvent.setDetails("Substitution");
                    events.add(subEvent);
                }
            }
        }

        matchEventRepository.saveAll(events);
    }

    private void appendGoalEvents(List<MatchEvent> events,
                                  long competitionId, int seasonNumber, int roundNumber,
                                  long teamId1, long teamId2, long scoringTeamId,
                                  List<Integer> goalMinutes, List<Human> outfieldPlayers,
                                  PersonalizedTactic tactic,
                                  String[] goalDescriptions,
                                  MatchEngineConfig.Events ev,
                                  Map<Long, com.footballmanagergamesimulator.model.PlayerSkills> skillsCache,
                                  Random random) {
        for (int minute : goalMinutes) {
            String description = goalDescriptions[random.nextInt(goalDescriptions.length)];
            Human scorer = resolveGoalScorer(description, outfieldPlayers, tactic, skillsCache, random);

            MatchEvent goalEvent = new MatchEvent();
            goalEvent.setCompetitionId(competitionId);
            goalEvent.setSeasonNumber(seasonNumber);
            goalEvent.setRoundNumber(roundNumber);
            goalEvent.setTeamId1(teamId1);
            goalEvent.setTeamId2(teamId2);
            goalEvent.setMinute(minute);
            goalEvent.setEventType("goal");
            goalEvent.setPlayerId(scorer.getId());
            goalEvent.setPlayerName(scorer.getName());
            goalEvent.setTeamId(scoringTeamId);
            goalEvent.setDetails(description);
            events.add(goalEvent);

            if (!"Penalty".equals(description) && random.nextDouble() < ev.getAssistProbability() && outfieldPlayers.size() > 1) {
                List<Human> possibleAssisters = outfieldPlayers.stream()
                        .filter(p -> p.getId() != scorer.getId())
                        .collect(Collectors.toList());
                if (!possibleAssisters.isEmpty()) {
                    Human assister = com.footballmanagergamesimulator.util.PositionScoringWeights.weightedPick(
                            possibleAssisters,
                            p -> {
                                com.footballmanagergamesimulator.model.PlayerSkills ps = skillsCache.get(p.getId());
                                int passing = ps != null ? ps.getPassing() : 0;
                                int vision = ps != null ? ps.getVision() : 0;
                                return com.footballmanagergamesimulator.util.PositionScoringWeights
                                        .assistWeight(p.getPosition(), passing, vision);
                            },
                            random);
                    MatchEvent assistEvent = new MatchEvent();
                    assistEvent.setCompetitionId(competitionId);
                    assistEvent.setSeasonNumber(seasonNumber);
                    assistEvent.setRoundNumber(roundNumber);
                    assistEvent.setTeamId1(teamId1);
                    assistEvent.setTeamId2(teamId2);
                    assistEvent.setMinute(minute);
                    assistEvent.setEventType("assist");
                    assistEvent.setPlayerId(assister.getId());
                    assistEvent.setPlayerName(assister.getName());
                    assistEvent.setTeamId(scoringTeamId);
                    assistEvent.setDetails("Assist");
                    events.add(assistEvent);
                }
            }
        }
    }

    /**
     * Resolve the goal scorer based on goal type and set piece taker assignments.
     * - "Penalty" → use designated penalty taker (if set)
     * - "Free kick" → use designated free kick taker (if set)
     * - Otherwise → random outfield player
     */
    private Human resolveGoalScorer(String goalDescription, List<Human> outfieldPlayers,
                                    PersonalizedTactic tactic,
                                    Map<Long, com.footballmanagergamesimulator.model.PlayerSkills> skillsCache,
                                    Random random) {
        if (tactic != null && outfieldPlayers != null && !outfieldPlayers.isEmpty()) {
            Long takerId = null;
            if ("Penalty".equals(goalDescription) && tactic.getPenaltyTakerId() != null) {
                takerId = tactic.getPenaltyTakerId();
            } else if ("Free kick".equals(goalDescription) && tactic.getFreeKickTakerId() != null) {
                takerId = tactic.getFreeKickTakerId();
            }
            if (takerId != null) {
                final long id = takerId;
                Human taker = outfieldPlayers.stream()
                        .filter(p -> p.getId() == id)
                        .findFirst().orElse(null);
                if (taker != null) return taker;
                // Taker not in outfield list (may be injured/subbed) — fallback to weighted
            }
        }
        // Same position + Finishing weighting as the live engine so watched and
        // un-watched matches distribute scorers consistently. (No stamina/pace
        // here: the batch path does not model per-minute fatigue.)
        return com.footballmanagergamesimulator.util.PositionScoringWeights.weightedPick(
                outfieldPlayers,
                p -> {
                    com.footballmanagergamesimulator.model.PlayerSkills ps = skillsCache.get(p.getId());
                    int finishing = ps != null ? ps.getFinishing() : 0;
                    return com.footballmanagergamesimulator.util.PositionScoringWeights
                            .scorerWeight(p.getPosition(), finishing);
                },
                random);
    }
}
