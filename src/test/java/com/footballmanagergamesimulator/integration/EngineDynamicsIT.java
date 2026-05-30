package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.LineupRatingService;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.TrainingService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private HumanRepository humanRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepo;
    @Autowired private RoundRepository roundRepository;

    private static final String TACTIC = "442";
    private static final long SEED = 20260528L;

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

    @Test
    @DisplayName("A batch (instant) match drains the fitness of the players who played")
    void batchMatchDrainsPlayedFitness() {
        String season = roundRepository.findById(1L).map(Round::getSeason).map(String::valueOf).orElse("1");

        // First league (typeId 1) that has round-1 fixtures to play.
        long leagueId = -1;
        List<CompetitionTeamInfoMatch> roundMatches = List.of();
        for (Competition c : competitionRepository.findAll()) {
            if (c.getTypeId() != 1) continue;
            List<CompetitionTeamInfoMatch> m =
                    matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(c.getId(), 1, season);
            if (!m.isEmpty()) { leagueId = c.getId(); roundMatches = m; break; }
        }
        Assumptions.assumeTrue(leagueId > 0 && !roundMatches.isEmpty(),
                "no league with round-1 fixtures in bootstrap");

        Set<Long> participantTeams = new HashSet<>();
        for (CompetitionTeamInfoMatch m : roundMatches) {
            participantTeams.add(m.getTeam1Id());
            participantTeams.add(m.getTeam2Id());
        }
        participantTeams.forEach(t -> setSquadFitness(t, 100.0));

        long sampleTeam = roundMatches.get(0).getTeam1Id();
        double before = meanFitness(sampleTeam);

        matchRoundSimulator.setRandomForTesting(new Random(SEED));
        try {
            matchRoundSimulator.simulateRound(String.valueOf(leagueId), "1");
        } finally {
            matchRoundSimulator.setRandomForTesting(new Random());
        }

        double after = meanFitness(sampleTeam);
        double floor = engineConfig.getStamina().getPostMatchFloor();
        // The eleven who played lose batch-match-fitness-drain each; benched players are
        // unchanged, so the squad mean drops but never breaches the post-match floor.
        assertThat(after)
                .as("playing an instant batch match must drain squad fitness (before=%.2f, after=%.2f)",
                        before, after)
                .isLessThan(before)
                .isGreaterThanOrEqualTo(floor);
    }

    // ==================== morale + home → effectivePower ====================

    @Test
    @DisplayName("Morale and home advantage scale effectivePower (the curve outcome tests skip)")
    void moraleAndHomeScaleEffectivePower() {
        double base = 1000.0;
        MatchEngineConfig.Power p = engineConfig.getPower();

        // Morale lifts power monotonically: floor + spread·(morale/100).
        double lowMorale = matchSimulationService.effectivePower(base, 30.0, false);
        double highMorale = matchSimulationService.effectivePower(base, 90.0, false);
        assertThat(highMorale)
                .as("higher morale must raise effectivePower (low=%.4f, high=%.4f)", lowMorale, highMorale)
                .isGreaterThan(lowMorale);

        // The away multiplier at morale m matches the configured curve exactly (no RNG).
        double expectedLow = base * (p.getMoraleFloor() + p.getMoraleSpread() * (30.0 / 100.0));
        assertThat(lowMorale).isCloseTo(expectedLow, within(1e-9));

        // Home advantage is a pure multiplier on top of the morale curve.
        double away = matchSimulationService.effectivePower(base, 70.0, false);
        double home = matchSimulationService.effectivePower(base, 70.0, true);
        assertThat(home)
                .as("home advantage must raise power (away=%.4f, home=%.4f)", away, home)
                .isGreaterThan(away);
        assertThat(home / away).isCloseTo(p.getHomeAdvantage(), within(1e-9));

        // TournamentEngine.playLeague (used by the outcome tests) calls calculateScores on the
        // raw power directly — so neither of these multipliers is applied there. This test pins
        // the dynamic factor the static-power pipeline omits.
        assertThat(p.getHomeAdvantage()).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("Per-match morale swing model: win>0, loss<0, giant-killing>favourite, favourite-loss harshest")
    void moraleSwingModelOrdering() {
        int n = 4000;
        matchSimulationService.setRandomForTesting(new Random(SEED));
        try {
            MatchEngineConfig.Morale m = engineConfig.getMorale();
            double big = m.getTierBigDiff();

            // Basic signs.
            assertThat(meanSwing("W", 0.0, n)).as("a win must raise morale on average").isGreaterThan(0);
            assertThat(meanSwing("L", 0.0, n)).as("a loss must lower morale on average").isLessThan(0);

            // Beating a much stronger team (giant-killing, diff < -big) boosts morale more than a
            // routine win as the big favourite (diff > big).
            double favouriteWin = meanSwing("W", big + 1, n);
            double giantKilling = meanSwing("W", -(big + 1), n);
            assertThat(giantKilling)
                    .as("giant-killing win must beat favourite win (giant=%.3f, fav=%.3f)",
                            giantKilling, favouriteWin)
                    .isGreaterThan(favouriteWin);

            // Losing as the big favourite is the harshest penalty; losing as the heavy underdog
            // (expected) is the mildest.
            double favouriteLoss = meanSwing("L", big + 1, n);
            double underdogLoss = meanSwing("L", -(big + 1), n);
            assertThat(favouriteLoss)
                    .as("favourite loss must be more negative than underdog loss (fav=%.3f, dog=%.3f)",
                            favouriteLoss, underdogLoss)
                    .isLessThan(underdogLoss);
        } finally {
            matchSimulationService.setRandomForTesting(new Random());
        }
    }

    /** Mean of {@code n} seeded samples of the real per-match morale swing model. */
    private double meanSwing(String result, double powerDiff, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += matchSimulationService.calculateMoraleChangeForResult(result, powerDiff);
        }
        return sum / n;
    }

    // ==================== pace: live-only, ignored by the batch path ====================

    @Test
    @DisplayName("Batch power ignores pace — mutating PlayerSkills.pace does not move the best-eleven rating")
    void batchPowerIgnoresPace() {
        long teamId = pickPopulatedTeamId();
        setSquadFitness(teamId, 100.0);
        setSquadMorale(teamId, 70.0);

        double baseline = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        List<Long> playerIds = players(teamId).stream().map(Human::getId).toList();
        List<PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(playerIds);
        assertThat(skills).as("bootstrapped squad must have PlayerSkills rows").isNotEmpty();

        skills.forEach(s -> s.setPace(1));
        playerSkillsRepository.saveAll(skills);
        double paceFloor = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        skills.forEach(s -> s.setPace(20));
        playerSkillsRepository.saveAll(skills);
        double paceMax = lineupRatingService.getBestElevenRatingByTactic(teamId, TACTIC);

        // Pace feeds ONLY the live engine (LiveMatchSimulationService). The batch power path
        // (LineupRatingService → effectivePower → calculateScores) never reads it, so the squad
        // rating is identical regardless of pace. This characterises the known limitation that
        // pace dynamics are absent from AI/batch simulation.
        assertThat(paceFloor)
                .as("pace=1 must not change the batch best-eleven rating (baseline=%.6f, paced=%.6f)",
                        baseline, paceFloor)
                .isEqualTo(baseline);
        assertThat(paceMax)
                .as("pace=20 must not change the batch best-eleven rating (baseline=%.6f, paced=%.6f)",
                        baseline, paceMax)
                .isEqualTo(baseline);
    }

    // ==================== attribute drift through training ====================

    @Test
    @DisplayName("Training drift: an old player's physical attributes decline while mental hold/grow")
    void trainingDriftDeclinesPhysicalForOldPlayer() {
        long teamId = pickPopulatedTeamId();

        Human old = players(teamId).stream()
                .filter(p -> !p.isRetired())
                .filter(p -> playerSkillsRepository.findPlayerSkillsByPlayerId(p.getId()).isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No player with a PlayerSkills row"));
        old.setAge(36);                 // deep into the physical-decline bracket (34+)
        old.setSeasonMatchesPlayed(0);  // no match-played growth bonus
        humanRepository.save(old);

        PlayerSkills start = playerSkillsRepository.findPlayerSkillsByPlayerId(old.getId()).orElseThrow();
        int physBefore = sumAttrs(start, PlayerSkillsService.PHYSICAL);
        int mentBefore = sumAttrs(start, PlayerSkillsService.MENTAL);

        // The training RNG is unseeded (probabilistic), so run many sessions and assert on the
        // statistical trend, not a single delta. At age 36 each session declines ~15% of physical
        // attrs (-0.3..-0.8) while mental attrs barely move, so the direction is effectively certain.
        int sessions = 150;
        for (int i = 0; i < sessions; i++) {
            // Clear training-injury rows so the target player isn't permanently skipped (injured
            // players are excluded from training); keeps the drift signal flowing.
            injuryRepository.deleteAll(injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0));
            trainingService.processTrainingSession(teamId, 1);
        }

        PlayerSkills end = playerSkillsRepository.findPlayerSkillsByPlayerId(old.getId()).orElseThrow();
        int physAfter = sumAttrs(end, PlayerSkillsService.PHYSICAL);
        int mentAfter = sumAttrs(end, PlayerSkillsService.MENTAL);

        int physDrop = physBefore - physAfter;
        int mentDrop = mentBefore - mentAfter;

        assertThat(physDrop)
                .as("old player's physical attributes must clearly decline after %d sessions "
                        + "(before=%d, after=%d)", sessions, physBefore, physAfter)
                .isGreaterThanOrEqualTo(5);
        assertThat(mentAfter)
                .as("mental attributes must hold or grow late-career (before=%d, after=%d)",
                        mentBefore, mentAfter)
                .isGreaterThanOrEqualTo(mentBefore - 3);
        assertThat(physDrop)
                .as("physical decline (%d) must exceed any mental change (%d) for an old player",
                        physDrop, mentDrop)
                .isGreaterThan(mentDrop);
    }

    private int sumAttrs(PlayerSkills skills, List<String> attrNames) {
        int sum = 0;
        for (String name : attrNames) {
            sum += PlayerSkillsService.GETTER_MAP.get(name).apply(skills);
        }
        return sum;
    }
}
