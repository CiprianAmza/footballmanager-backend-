package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Human-match rating + scorer assignment, extracted from
 * {@link CompetitionController}. Owns the three load-bearing helpers behind
 * full-engine human matches: best-eleven rating with morale/fitness/role
 * weighting, the tactical multiplier applied to that rating, and the scorer
 * distribution that turns a final score into Scorer rows.
 *
 * <p>{@link MatchSimulationOrchestrator} and the interactive-live-match
 * commit path on the controller call directly into this service; the
 * controller no longer carries delegate wrappers for these methods.
 *
 * <p>Back-refs: the controller is held via {@link Lazy @Lazy} for the
 * comp-type cache accessors (avoids re-deriving comp-type id sets per
 * scorer-leaderboard write), and the orchestrator is held via
 * {@link Lazy @Lazy} for per-round injury lookups (the orchestrator
 * already has DB fallback when called outside simulateRound).
 */
@Service
public class LineupRatingService {

    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private ScorerLeaderboardRepository scorerLeaderboardRepository;

    @Autowired private TacticService tacticService;
    @Autowired private PlayerRoleService playerRoleService;
    @Autowired private PlayerInstructionService playerInstructionService;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private CompetitionService competitionService;
    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private TeamPostMatchService teamPostMatchService;

    @Autowired @Lazy private TacticController tacticController;
    @Autowired private GameStateService gameStateService;
    @Autowired @Lazy private MatchSimulationOrchestrator matchSimulationOrchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Best-eleven rating with morale + fitness + role suitability multipliers.
     * Sources the starting XI from the team's personalized tactic JSON when
     * available, falling back to the auto-picked best XI when not.
     */
    public double getBestElevenRatingByTactic(long teamId, String tactic) {

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);

        if (personalizedTacticOpt.isPresent()) {
            PersonalizedTactic personalized = personalizedTacticOpt.get();
            double rating = 0;
            Set<Long> injuredIds = matchSimulationOrchestrator.roundInjuredIds(teamId);

            try {
                List<FormationData> formationDataList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                for (FormationData data : formationDataList) {

                    if (data.getPositionIndex() >= 30) continue;

                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    if (injuredIds.contains(player.getId())) continue;

                    String tacticPosition = tacticService.getPositionFromIndex(data.getPositionIndex());
                    String naturalPos = TacticService.getBasePosition(player.getPosition());
                    String usedPos = TacticService.getBasePosition(tacticPosition);

                    // Base = position-weighted attribute value (config-driven). Role suitability,
                    // when a role is set, blends into the base via PlayerRoleService.
                    PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId()).orElse(null);
                    double base;
                    if (skills != null) {
                        double positional = playerValueService.computePositionalValue(skills, usedPos);
                        base = (data.getRole() != null && !data.getRole().isEmpty())
                                ? playerRoleService.computeEffectiveRating(skills, data.getRole(), positional)
                                : positional;
                    } else {
                        base = player.getRating();
                    }

                    // Familiarity (slot vs natural position) replaces the old flat /2 penalty;
                    // morale + fitness are per-player; the instruction multiplier is layered on.
                    double instructionMultiplier = playerInstructionService.computeInstructionMultiplier(
                            data.getInstructions(), usedPos, "general");
                    rating += base
                            * playerValueService.familiarityFactor(naturalPos, usedPos)
                            * playerValueService.moraleFactor(player.getMorale())
                            * playerValueService.fitnessFactor(player.getFitness())
                            * instructionMultiplier;
                }

                // Team talk + home advantage are applied centrally at the scoring call
                // (MatchSimulationService.effectiveTeamPower); this returns the squad value
                // with per-player morale/fitness/familiarity already included.
                return rating;

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Fallback: auto-picked best eleven (no roles/instructions in this path).
        List<TacticController.StarterSlot> starters = tacticController.getBestElevenWithSlots(String.valueOf(teamId), tactic);

        List<Long> ids = starters.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skillsById = new java.util.HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) {
            skillsById.put(s.getPlayerId(), s);
        }

        double fallbackRating = 0;
        for (TacticController.StarterSlot slot : starters) {
            PlayerView pv = slot.player();
            String naturalPos = TacticService.getBasePosition(pv.getPosition());
            String usedPos = slot.usedPosition();
            PlayerSkills skills = skillsById.get(pv.getId());
            fallbackRating += skills != null
                    ? playerValueService.evaluatePlayer(skills, naturalPos, usedPos, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), naturalPos, usedPos, pv.getMorale(), pv.getFitness());
        }

        // Team talk applied centrally via effectiveTeamPower (see above).
        return fallbackRating;
    }

    /**
     * Mentality × power-difference × tempo/passing/possession combinatorics
     * applied as a percentage swing on the base team rating. This is the
     * detailed live-flow version; the simpler twin previously in
     * {@code MatchSimulationService} had zero callers and was pruned.
     */
    public double adjustTeamPowerByTacticalProperties(double teamRating, double opponentRating, PersonalizedTactic teamTactic) {

        double difference = teamRating - opponentRating;
        int percentage = 0;

        String mentality = teamTactic.getMentality() != null ? teamTactic.getMentality() : "Balanced";
        String timeWasting = teamTactic.getTimeWasting() != null ? teamTactic.getTimeWasting() : "Sometimes";
        String inPossession = teamTactic.getInPossession() != null ? teamTactic.getInPossession() : "Standard";
        String passingType = teamTactic.getPassingType() != null ? teamTactic.getPassingType() : "Normal";
        String tempo = teamTactic.getTempo() != null ? teamTactic.getTempo() : "Standard";

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

        if ("Keep Ball".equals(inPossession)) {
            if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Very Attacking".equals(mentality)) percentage -= 15;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15;

        } else if ("Free Ball Early".equals(inPossession)) {
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15;
        }

        if ("Short".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage += 5;
            else if ("Lower".equals(tempo)) percentage += 10;
            else if ("Standard".equals(tempo)) percentage += 15;
            else if ("Higher".equals(tempo)) percentage += 20;
            else if ("Much Higher".equals(tempo)) percentage += 25;

        } else if ("Normal".equals(passingType) || "Standard".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 10;
            else if ("Lower".equals(tempo)) percentage -= 5;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 5;
            else if ("Much Higher".equals(tempo)) percentage += 10;

        } else if ("Long".equals(passingType) || "Direct".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 30;
            else if ("Lower".equals(tempo)) percentage -= 25;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 25;
            else if ("Much Higher".equals(tempo)) percentage += 30;
        }

        return teamRating + (teamRating * percentage / 100D);
    }

    /**
     * Distribute {@code teamScore} goals across the lineup and persist the
     * resulting {@link Scorer} rows plus per-player leaderboard updates.
     * Handles personalized-tactic JSON sourcing with an auto-best-eleven
     * fallback; respects injuries via the orchestrator's per-round cache.
     */
    public void getScorersForTeam(long teamId, long opponentTeamId, int teamScore, int opponentScore, String tactic, long competitionId) {

        Long competitionTypeIdObj = competitionRepository.findTypeIdById(competitionId);
        long competitionTypeId = competitionTypeIdObj != null ? competitionTypeIdObj : 0L;
        String teamName = teamRepository.findNameById(teamId);
        String opponentName = teamRepository.findNameById(opponentTeamId);
        String competitionName = competitionRepository.findNameById(competitionId);

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        List<Scorer> possibleScorers = new ArrayList<>();
        List<Scorer> substitutions = new ArrayList<>();

        boolean loadedSuccessfully = false;

        if (personalizedTacticOpt.isPresent()) {
            try {
                PersonalizedTactic personalized = personalizedTacticOpt.get();
                Set<Long> injuredIds = matchSimulationOrchestrator.roundInjuredIds(teamId);

                List<FormationData> formationList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                for (FormationData data : formationList) {
                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    if (injuredIds.contains(player.getId())) continue;

                    Scorer scorer = new Scorer();
                    scorer.setPlayerId(player.getId());
                    scorer.setSeasonNumber(gameStateService.currentSeason());
                    scorer.setTeamId(teamId);
                    scorer.setOpponentTeamId(opponentTeamId);
                    scorer.setPosition(player.getPosition());
                    scorer.setTeamScore(teamScore);
                    scorer.setOpponentScore(opponentScore);
                    scorer.setCompetitionId(competitionId);
                    scorer.setCompetitionTypeId((int) competitionTypeId);
                    scorer.setTeamName(teamName);
                    scorer.setOpponentTeamName(opponentName);
                    scorer.setCompetitionName(competitionName);
                    // Seed base rating so weight = posMul × rating²/70 has signal at sample
                    // time; assignMatchRatings() overwrites this with the post-match rating.
                    scorer.setRating(player.getRating());

                    if (data.getPositionIndex() >= 30) {
                        scorer.setSubstitute(true);
                        substitutions.add(scorer);
                    } else {
                        scorer.setSubstitute(false);
                        possibleScorers.add(scorer);
                    }
                }

                if (!possibleScorers.isEmpty()) {
                    loadedSuccessfully = true;
                }

            } catch (Exception e) {
                System.err.println("Error parsing tactic JSON for team " + teamId + ": " + e.getMessage());
            }
        }

        if (!loadedSuccessfully) {
            List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);
            playerViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(gameStateService.currentSeason());
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(false);
                return scorer;
            }).forEach(possibleScorers::add);

            List<PlayerView> substitutionViews = tacticController.getSubstitutions(String.valueOf(teamId), tactic);
            substitutionViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(gameStateService.currentSeason());
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(true);
                return scorer;
            }).forEach(substitutions::add);
        }

        Random random = new Random();
        int substitutesDone = random.nextInt(0, Math.min(6, substitutions.size() + 1));
        if (!substitutions.isEmpty()) {
            Collections.shuffle(substitutions);
            for (int i = 0; i < Math.min(substitutesDone, substitutions.size()); i++) {
                possibleScorers.add(substitutions.get(i));
            }
        }

        List<Pair<Scorer, Double>> weightedPlayers = new ArrayList<>();
        for (Scorer scorer : possibleScorers) {
            if ("GK".equals(scorer.getPosition())) continue;
            double weight = competitionService.getDifferentValueForScoringBasedOnPosition(scorer);
            if (weight <= 0) weight = 0.1;
            weightedPlayers.add(new Pair<>(scorer, weight));
        }

        if (!weightedPlayers.isEmpty()) {
            for (int i = 0; i < teamScore; i++) {
                try {
                    EnumeratedDistribution<Scorer> distribution = new EnumeratedDistribution<>(weightedPlayers);
                    Scorer selected = distribution.sample();
                    selected.setGoals(selected.getGoals() + 1);

                    // ~75% of goals have an assist; assister weighted by creative-position
                    // bias and must be a different player.
                    if (random.nextDouble() < 0.75) {
                        List<Pair<Scorer, Double>> assistCandidates = new ArrayList<>();
                        for (Scorer s : possibleScorers) {
                            if (s.getPlayerId() == selected.getPlayerId()) continue;
                            if ("GK".equals(s.getPosition())) continue;
                            double w = getAssistWeight(s);
                            if (w > 0) assistCandidates.add(new Pair<>(s, w));
                        }
                        if (!assistCandidates.isEmpty()) {
                            EnumeratedDistribution<Scorer> assistDist = new EnumeratedDistribution<>(assistCandidates);
                            Scorer assister = assistDist.sample();
                            assister.setAssists(assister.getAssists() + 1);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Distribution error (negative weights?): " + e.getMessage());
                }
            }
        }

        matchSimulationService.assignMatchRatings(possibleScorers, teamScore, opponentScore);

        Set<Long> leagueCompIds = gameStateService.getLeagueCompetitionIdsCached();
        Set<Long> cupCompIds = gameStateService.getCupCompetitionIdsCached();
        Set<Long> secondLeagueCompIds = gameStateService.getSecondLeagueCompetitionIdsCached();

        for (Scorer scorer : possibleScorers) {

            scorerRepository.save(scorer);

            Optional<Human> possiblePlayer = humanRepository.findById(scorer.getPlayerId());
            if (possiblePlayer.isPresent()) {
                Human player = possiblePlayer.get();

                ScorerLeaderboardEntry scorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(player.getId()).orElseGet(() -> {
                    ScorerLeaderboardEntry newEntry = new ScorerLeaderboardEntry();
                    newEntry.setPlayerId(player.getId());
                    newEntry.setName(player.getName());
                    newEntry.setPosition(player.getPosition());
                    newEntry.setTeamId(player.getTeamId() != null ? player.getTeamId() : 0);
                    newEntry.setTeamName(player.getTeamId() != null ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");
                    newEntry.setActive(true);
                    newEntry.setCurrentRating(player.getRating());
                    newEntry.setBestEverRating(player.getRating());
                    newEntry.setSeasonOfBestEverRating(gameStateService.currentSeason());
                    newEntry.setAge(player.getAge());
                    return scorerLeaderboardRepository.save(newEntry);
                });
                scorerLeaderboardEntry.setAge(player.getAge());
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
                if (player.getRating() > scorerLeaderboardEntry.getBestEverRating()) {
                    scorerLeaderboardEntry.setBestEverRating(player.getRating());
                    scorerLeaderboardEntry.setSeasonOfBestEverRating(gameStateService.currentSeason());
                }
                scorerLeaderboardEntry.setAge(player.getAge());
                scorerLeaderboardEntry.setCurrentRating(player.getRating());

                scorerLeaderboardEntry.setMatches(scorerLeaderboardEntry.getMatches() + 1);
                scorerLeaderboardEntry.setGoals(scorerLeaderboardEntry.getGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGoals(scorerLeaderboardEntry.getCurrentSeasonGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGames(scorerLeaderboardEntry.getCurrentSeasonGames() + 1);

                if (leagueCompIds.contains(competitionId)) {
                    scorerLeaderboardEntry.setLeagueGoals(scorerLeaderboardEntry.getLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setLeagueMatches(scorerLeaderboardEntry.getLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGames(scorerLeaderboardEntry.getCurrentSeasonLeagueGames() + 1);
                } else if (cupCompIds.contains(competitionId)) {
                    scorerLeaderboardEntry.setCupGoals(scorerLeaderboardEntry.getCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCupMatches(scorerLeaderboardEntry.getCupMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonCupGoals(scorerLeaderboardEntry.getCurrentSeasonCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonCupGames(scorerLeaderboardEntry.getCurrentSeasonCupGames() + 1);
                } else if (secondLeagueCompIds.contains(competitionId)) {
                    scorerLeaderboardEntry.setSecondLeagueGoals(scorerLeaderboardEntry.getSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setSecondLeagueMatches(scorerLeaderboardEntry.getSecondLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGames() + 1);
                }
                scorerLeaderboardRepository.save(scorerLeaderboardEntry);
            }
        }
    }

    /**
     * Position + rating weighted assist contribution. Wide mids and AMs get
     * the biggest bias, centre-backs the smallest; GKs are excluded upstream.
     * Rating scales linearly (not squared) so assists spread more evenly than
     * goals across the squad. Substitutes get half weight.
     */
    private double getAssistWeight(Scorer scorer) {
        Map<String, Double> positionToValue = Map.of(
                "GK", 0D,
                "DL", 0.8,
                "DR", 0.8,
                "DC", 0.4,
                "ML", 2.5,
                "MR", 2.5,
                "MC", 2.0,
                "ST", 1.2);
        double posMul = positionToValue.getOrDefault(scorer.getPosition(), 1.0);
        double ratingFactor = Math.max(scorer.getRating(), 1.0);
        double w = posMul * ratingFactor;
        if (scorer.isSubstitute()) w /= 2;
        return Math.max(w, 0);
    }
}
