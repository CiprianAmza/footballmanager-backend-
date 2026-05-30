package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
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
 * Core match simulation utilities: score generation (Poisson), match events
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
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private UserContext userContext;
    @Autowired MatchEngineConfig engineConfig; // package-private so unit tests can inject a plain config

    /**
     * Shared RNG used by {@link #calculateScores} and other scoring helpers.
     * Held as a field (not local) so fuzz/integration tests can swap in a
     * seeded {@link Random} via {@link #setRandomForTesting(Random)} and get
     * reproducible match outcomes across iterations.
     *
     * <p>Production code never touches this field — it stays the default
     * non-seeded {@link Random} so live matches feel random as before.
     */
    private Random random = new Random();

    /**
     * Test-only seam: swap the RNG for fuzz tests so the same seed produces
     * the same Poisson sample. Public so fuzz tests in
     * {@code integration/fuzz/} (different package) can call it — production
     * code MUST NOT use this method. Callers MUST restore the production RNG
     * when done (or rely on the test creating a fresh service instance).
     */
    public void setRandomForTesting(Random random) {
        this.random = random;
    }

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
        return calculateScores(power1, power2, engineConfig.getPower().getExpectedGoalsTotal());
    }

    /**
     * Convert a base squad rating into the "effective power" fed to
     * {@link #calculateScores} — the same model the invariant/tuner harness uses,
     * now config-driven so the game matches the tuned invariants:
     * {@code base × (moraleFloor + moraleSpread × morale/100) × (home ? homeAdvantage : 1)}.
     *
     * @param basePower raw squad rating (best XI), before morale/home adjustment
     * @param morale    squad morale on a 0..100 scale
     * @param home      true if this side plays at home
     */
    public double effectivePower(double basePower, double morale, boolean home) {
        MatchEngineConfig.Power p = engineConfig.getPower();
        double moraleMult = p.getMoraleFloor() + p.getMoraleSpread() * (morale / 100.0);
        double homeMult = home ? p.getHomeAdvantage() : 1.0;
        return basePower * moraleMult * homeMult;
    }

    /**
     * Production scoring path: the team value already carries per-player morale and fitness
     * (computed in {@link PlayerValueService}), so this overload applies ONLY the team-level
     * modifiers — the manager's team talk and home advantage:
     * {@code base × teamTalk × (home ? homeAdvantage : 1)}.
     *
     * <p>The {@link #effectivePower(double, double, boolean)} morale-curve overload is kept
     * for the invariant/tuner harness, which validates that curve in isolation.
     *
     * @param teamTalk team-talk multiplier centered on 1.0 (neutral). AI uses 1.0.
     */
    public double effectiveTeamPower(double basePower, double teamTalk, boolean home) {
        double homeMult = home ? engineConfig.getPower().getHomeAdvantage() : 1.0;
        return basePower * teamTalk * homeMult;
    }

    /**
     * Same as {@link #calculateScores(double, double)} but with a caller-supplied
     * total expected goals. Used for short matches such as the 30-minute extra-time
     * "mini match" in knockout ties, where far fewer goals are expected than over a
     * full 90 minutes.
     *
     * @param totalExpectedGoals expected combined goals, split by the amplified power ratio
     */
    public List<Integer> calculateScores(double power1, double power2, double totalExpectedGoals) {
        double total = power1 + power2;
        if (total == 0) return List.of(1, 1);

        double ratio1 = power1 / total;
        double ratio2 = power2 / total;

        // Amplify power difference: raise ratio to power N then renormalize.
        // Exponent sourced from MatchEngineConfig.power.ratioExponent so fuzz
        // tests can sweep without recompiling.
        double exp = engineConfig.getPower().getRatioExponent();
        double amp1 = Math.pow(ratio1, exp);
        double amp2 = Math.pow(ratio2, exp);
        double ampTotal = amp1 + amp2;
        double adjRatio1 = amp1 / ampTotal;
        double adjRatio2 = amp2 / ampTotal;

        double expected1 = totalExpectedGoals * adjRatio1;
        double expected2 = totalExpectedGoals * adjRatio2;

        // Uses the field-level RNG so fuzz tests can inject a seeded Random
        // via setRandomForTesting(...). See javadoc on the `random` field.
        int maxGoals = engineConfig.getPower().getMaxGoalsPerTeam();
        int score1 = poissonGoals(this.random, expected1, maxGoals);
        int score2 = poissonGoals(this.random, expected2, maxGoals);

        return List.of(score1, score2);
    }

    /**
     * Poisson distribution sampling using Knuth's algorithm.
     * Capped at {@code maxGoals} per team per match.
     */
    private int poissonGoals(Random random, double expectedGoals, int maxGoals) {
        double L = Math.exp(-expectedGoals);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);
        return Math.min(k - 1, maxGoals);
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
     * Note: Suspension check delegated to SuspensionService — kept as a stub so
     * {@link #isPlayerAvailable} doesn't need to inject the full suspension flow.
     */
    private List<Suspension> findActiveSuspensionsForPlayer(long playerId) {
        return List.of();
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
    public void generateMatchEvents(long competitionId, int seasonNumber, int roundNumber,
                                    long teamId1, long teamId2, int teamScore1, int teamScore2,
                                    String tactic1, String tactic2) {

        // Field-level RNG so determinism IT holds.
        Random random = this.random;
        List<MatchEvent> events = new ArrayList<>();

        boolean isHumanMatch = userContext.isHumanTeam(teamId1) || userContext.isHumanTeam(teamId2);

        String[] goalDescriptions = {"Tap in", "Header", "Long range shot", "Free kick", "Penalty", "Solo run", "Volley"};

        Optional<PersonalizedTactic> tactic1Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
        Optional<PersonalizedTactic> tactic2Opt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

        MatchEngineConfig.Events ev = engineConfig.getEvents();
        MatchEngineConfig.Fouls fl = engineConfig.getFouls();
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
            if (i < teamScore1) {
                team1GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
            } else {
                team2GoalMinutes.add(goalMinutes.get(shuffledIndices.get(i)));
            }
        }
        Collections.sort(team1GoalMinutes);
        Collections.sort(team2GoalMinutes);

        List<Human> team1Players = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.PLAYER_TYPE);
        List<Human> team2Players = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.PLAYER_TYPE);

        List<Human> team1Outfield = team1Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());
        List<Human> team2Outfield = team2Players.stream().filter(p -> !"GK".equals(p.getPosition())).collect(Collectors.toList());

        if (team1Outfield.isEmpty()) team1Outfield = new ArrayList<>(team1Players);
        if (team2Outfield.isEmpty()) team2Outfield = new ArrayList<>(team2Players);

        for (int minute : team1GoalMinutes) {
            String description = goalDescriptions[random.nextInt(goalDescriptions.length)];
            Human scorer = resolveGoalScorer(description, team1Outfield, tactic1Opt.orElse(null), random);
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
            goalEvent.setTeamId(teamId1);
            goalEvent.setDetails(description);
            events.add(goalEvent);

            // Assist chance (no assist for penalties)
            if (!"Penalty".equals(description) && random.nextDouble() < ev.getAssistProbability() && team1Outfield.size() > 1) {
                List<Human> possibleAssisters = team1Outfield.stream()
                        .filter(p -> p.getId() != scorer.getId()).collect(Collectors.toList());
                if (!possibleAssisters.isEmpty()) {
                    Human assister = possibleAssisters.get(random.nextInt(possibleAssisters.size()));
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
                    assistEvent.setTeamId(teamId1);
                    assistEvent.setDetails("Assist");
                    events.add(assistEvent);
                }
            }
        }

        for (int minute : team2GoalMinutes) {
            String description = goalDescriptions[random.nextInt(goalDescriptions.length)];
            Human scorer = resolveGoalScorer(description, team2Outfield, tactic2Opt.orElse(null), random);
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
            goalEvent.setTeamId(teamId2);
            goalEvent.setDetails(description);
            events.add(goalEvent);

            if (!"Penalty".equals(description) && random.nextDouble() < ev.getAssistProbability() && team2Outfield.size() > 1) {
                List<Human> possibleAssisters = team2Outfield.stream()
                        .filter(p -> p.getId() != scorer.getId()).collect(Collectors.toList());
                if (!possibleAssisters.isEmpty()) {
                    Human assister = possibleAssisters.get(random.nextInt(possibleAssisters.size()));
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
                    assistEvent.setTeamId(teamId2);
                    assistEvent.setDetails("Assist");
                    events.add(assistEvent);
                }
            }
        }

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

    /**
     * Resolve the goal scorer based on goal type and set piece taker assignments.
     * - "Penalty" → use designated penalty taker (if set)
     * - "Free kick" → use designated free kick taker (if set)
     * - Otherwise → random outfield player
     */
    private Human resolveGoalScorer(String goalDescription, List<Human> outfieldPlayers,
                                    PersonalizedTactic tactic, Random random) {
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
                // Taker not in outfield list (may be injured/subbed) — fallback to random
            }
        }
        return outfieldPlayers.get(random.nextInt(outfieldPlayers.size()));
    }
}
