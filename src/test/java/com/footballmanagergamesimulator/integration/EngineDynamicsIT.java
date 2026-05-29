package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.LineupRatingService;
import com.footballmanagergamesimulator.service.TrainingService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamics invariants exercised through the REAL production services — not the
 * pure {@code calculateScores} function. The outcome/fuzz tests run the engine
 * on a STATIC "sum of top-11 ratings" power; this suite proves that the live
 * inputs that feed real matches actually move the numbers:
 *
 * <ul>
 *   <li><b>Fitness</b> and <b>morale</b> scale a team's effective squad strength
 *       via {@link LineupRatingService#getBestElevenRatingByTactic} (per-player
 *       {@code fitnessMultiplier}/{@code moraleMultiplier}, no RNG → exact).</li>
 *   <li><b>Injuries</b> remove a starter from the best eleven (the rating drops
 *       because the injured player is excluded via the round injury lookup).</li>
 *   <li><b>Training</b> recovers player fitness through
 *       {@link TrainingService#processTrainingSession} (always non-decreasing).</li>
 * </ul>
 *
 * <p>Runs in the default {@code mvn verify} suite (fast, deterministic). The
 * class is {@link Transactional} so every mutation rolls back, keeping the
 * shared Spring context clean for sibling integration tests.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Engine dynamics on the real pipeline: fitness / morale / injury / training")
class EngineDynamicsIT {

    @Autowired private LineupRatingService lineupRatingService;
    @Autowired private TrainingService trainingService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private TeamRepository teamRepository;

    private static final String TACTIC = "442";

    /** First team that fields a full squad (≥ 11 non-retired players). */
    private long pickPopulatedTeamId() {
        for (Team team : teamRepository.findAll()) {
            long count = players(team.getId()).stream().filter(p -> !p.isRetired()).count();
            if (count >= 11) return team.getId();
        }
        throw new IllegalStateException("No bootstrapped team has a full squad");
    }

    private List<Human> players(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
    }

    private void setSquadFitness(long teamId, double fitness) {
        List<Human> squad = players(teamId);
        squad.forEach(p -> p.setFitness(fitness));
        humanRepository.saveAll(squad);
    }

    private void setSquadMorale(long teamId, double morale) {
        List<Human> squad = players(teamId);
        squad.forEach(p -> p.setMorale(morale));
        humanRepository.saveAll(squad);
    }

    @Test
    @DisplayName("Full fitness yields a strictly higher squad rating than reduced fitness")
    void fitnessScalesSquadRating() {
        long teamId = pickPopulatedTeamId();
        setSquadMorale(teamId, 70.0); // neutral, so only fitness varies

        setSquadFitness(teamId, 100.0);
        double ratingFull = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        setSquadFitness(teamId, 70.0); // multiplier floor = max(0.7, fitness/100) = 0.7
        double ratingLow = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        assertThat(ratingFull)
                .as("squad rating must be positive at full fitness")
                .isGreaterThan(0);
        // Every starter shares the same fitness multiplier (max(0.7, fitness/100)),
        // so dropping to 70 fitness must strictly lower the rating without collapsing it.
        assertThat(ratingLow)
                .as("reduced fitness (70 → ×0.7) must lower the squad rating "
                        + "(full=%.4f, low=%.4f)", ratingFull, ratingLow)
                .isLessThan(ratingFull)
                .isGreaterThan(ratingFull * 0.5);
    }

    @Test
    @DisplayName("Higher morale yields a higher squad rating than lower morale")
    void moraleNudgesSquadRating() {
        long teamId = pickPopulatedTeamId();
        setSquadFitness(teamId, 100.0); // fix fitness so only morale varies

        setSquadMorale(teamId, 100.0);
        double ratingHigh = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        setSquadMorale(teamId, 40.0);
        double ratingLow = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        assertThat(ratingHigh)
                .as("high morale must produce a strictly higher squad rating than low morale "
                        + "(high=%.4f, low=%.4f)", ratingHigh, ratingLow)
                .isGreaterThan(ratingLow);
    }

    @Test
    @DisplayName("Injuring the best player lowers the best-eleven rating")
    void injuryExcludesPlayerFromBestEleven() {
        long teamId = pickPopulatedTeamId();
        setSquadFitness(teamId, 100.0);
        setSquadMorale(teamId, 70.0);

        double ratingHealthy = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        Human best = players(teamId).stream()
                .filter(p -> !p.isRetired())
                .max(Comparator.comparingDouble(Human::getRating))
                .orElseThrow();

        Injury injury = new Injury();
        injury.setPlayerId(best.getId());
        injury.setTeamId(teamId);
        injury.setInjuryType("Test");
        injury.setSeverity("Serious");
        injury.setDaysRemaining(14);
        injury.setSeasonNumber(1);
        injuryRepository.save(injury);

        double ratingInjured = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        assertThat(ratingInjured)
                .as("excluding the top-rated player must lower the best-eleven rating "
                        + "(healthy=%.4f, injured=%.4f)", ratingHealthy, ratingInjured)
                .isLessThan(ratingHealthy);
    }

    @Test
    @DisplayName("Training recovers squad fitness (never decreases it)")
    void trainingRecoversFitness() {
        long teamId = pickPopulatedTeamId();
        setSquadFitness(teamId, 50.0);
        double before = meanFitness(teamId);

        trainingService.processTrainingSession(teamId, 1);

        double after = meanFitness(teamId);
        assertThat(after)
                .as("a training session must not lower mean squad fitness "
                        + "(before=%.2f, after=%.2f)", before, after)
                .isGreaterThan(before);
    }

    private double meanFitness(long teamId) {
        List<Human> squad = players(teamId).stream().filter(p -> !p.isRetired()).toList();
        return squad.stream().mapToDouble(Human::getFitness).average().orElse(0);
    }
}
