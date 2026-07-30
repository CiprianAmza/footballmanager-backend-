package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Pure, deterministic inverse-CDF sampler for one canonical match evaluation. */
@Component
public final class CanonicalScoreSampler {
    private static final double SUM_TOLERANCE = 1e-9;
    private final CompartmentEngineConfig config;

    /** Pure-test constructor: preserves the supplied analytical PMFs exactly. */
    public CanonicalScoreSampler() {
        this.config = null;
    }

    /** Production constructor: activates red cards and explicit SHOOTER events. */
    @Autowired
    public CanonicalScoreSampler(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public GoalSample sample(CanonicalMatchEvaluation evaluation, long seed) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (config == null) return sampleAnalytical(evaluation, seed);

        SplittableRandom random = new SplittableRandom(seed);
        ShooterMatchMechanic mechanic = new ShooterMatchMechanic(config);
        RedCard homeRed = sampleRedCard(evaluation.home().team(), mechanic, random);
        RedCard awayRed = sampleRedCard(evaluation.away().team(), mechanic, random);

        // SHOOTER is a separate, explicit source of goals. Resolve it before the
        // collective score, exactly as the domain rule states. A red card is sampled
        // first because an eliminated SHOOTER cannot take his special shots.
        ShooterGoals homeShooter = sampleShooterGoals(evaluation.home().team(), homeRed,
                evaluation.away().team().pressing(), mechanic, random);
        ShooterGoals awayShooter = sampleShooterGoals(evaluation.away().team(), awayRed,
                evaluation.home().team().pressing(), mechanic, random);

        PassingStyleMatchMechanic passingMechanic = new PassingStyleMatchMechanic(config);
        double homeControl = passingMechanic.suppression(evaluation.home().team().passingStyle(),
                homeRed.playerId(), evaluation.away().team());
        double awayControl = passingMechanic.suppression(evaluation.away().team().passingStyle(),
                awayRed.playerId(), evaluation.home().team());

        EffectivePower homePower = effectivePower(evaluation.home().team(), homeRed);
        EffectivePower awayPower = effectivePower(evaluation.away().team(), awayRed);
        GoalProbabilityFormula.MatchProbability probability = new GoalProbabilityFormula(config).expectedGoals(
                homePower.attack(), awayPower.protection(), awayPower.attack(), homePower.protection(),
                evaluation.combinedOpenness(), evaluation.venue() == MatchVenue.HOME);

        GoalProbabilityFormula.GoalDistribution home = probability.homeGoals();
        GoalProbabilityFormula.GoalDistribution away = probability.awayGoals();
        validate(home);
        validate(away);
        int homeCollective = retainedGoals(sample(home, random.nextDouble()), awayControl, random);
        int awayCollective = retainedGoals(sample(away, random.nextDouble()), homeControl, random);

        PassingGoals homePassing = samplePassingGoals(evaluation.home().team(), evaluation.away().team(),
                homeRed, awayRed, homeControl, passingMechanic, random);
        PassingGoals awayPassing = samplePassingGoals(evaluation.away().team(), evaluation.home().team(),
                awayRed, homeRed, awayControl, passingMechanic, random);
        double homeEffectiveAttack = homePower.attack() * (1.0 - awayControl);
        double awayEffectiveAttack = awayPower.attack() * (1.0 - homeControl);
        double homeXg = probability.homeXg() * (1.0 - awayControl) + homePassing.expectedGoals();
        double awayXg = probability.awayXg() * (1.0 - homeControl) + awayPassing.expectedGoals();
        return new GoalSample(homeCollective + homeShooter.goals() + homePassing.goals(),
                awayCollective + awayShooter.goals() + awayPassing.goals(),
                homeCollective, awayCollective,
                homeRed.playerId(), awayRed.playerId(),
                homeShooter.playerId(), awayShooter.playerId(),
                homeShooter.goals(), awayShooter.goals(),
                homeEffectiveAttack, homePower.protection(), awayEffectiveAttack, awayPower.protection(),
                homeXg, awayXg,
                homeShooter.shots(), awayShooter.shots(),
                homePassing.playerId(), awayPassing.playerId(),
                homePassing.goals(), awayPassing.goals(),
                homePassing.opportunities(), awayPassing.opportunities(),
                homeControl, awayControl);
    }

    private GoalSample sampleAnalytical(CanonicalMatchEvaluation evaluation, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        GoalProbabilityFormula.GoalDistribution home = evaluation.probability().homeGoals();
        GoalProbabilityFormula.GoalDistribution away = evaluation.probability().awayGoals();
        validate(home);
        validate(away);
        int homeGoals = sample(home, random.nextDouble());
        int awayGoals = sample(away, random.nextDouble());
        return new GoalSample(homeGoals, awayGoals, homeGoals, awayGoals,
                null, null, null, null, 0, 0,
                evaluation.home().team().attack(), evaluation.home().team().attackProtection(),
                evaluation.away().team().attack(), evaluation.away().team().attackProtection(),
                evaluation.probability().homeXg(), evaluation.probability().awayXg(), 0, 0,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

    /** Sample a shorter canonical period (for example extra time) from scaled canonical xG. */
    public GoalSample sampleScaled(CanonicalMatchEvaluation evaluation, long seed, double scale,
                                   CompartmentEngineConfig config) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(config, "config");
        if (!Double.isFinite(scale) || scale < 0.0) {
            throw new IllegalArgumentException("scale must be finite and non-negative");
        }
        GoalProbabilityFormula formula = new GoalProbabilityFormula(config);
        GoalProbabilityFormula.GoalDistribution home = formula.predictiveGoals(
                evaluation.probability().homeXg() * scale);
        GoalProbabilityFormula.GoalDistribution away = formula.predictiveGoals(
                evaluation.probability().awayXg() * scale);
        SplittableRandom random = new SplittableRandom(seed);
        validate(home);
        validate(away);
        int homeGoals = sample(home, random.nextDouble());
        int awayGoals = sample(away, random.nextDouble());
        return new GoalSample(homeGoals, awayGoals, homeGoals, awayGoals,
                null, null, null, null, 0, 0,
                evaluation.home().team().attack(), evaluation.home().team().attackProtection(),
                evaluation.away().team().attack(), evaluation.away().team().attackProtection(),
                home.mean(), away.mean(), 0, 0,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

    private static int sample(GoalProbabilityFormula.GoalDistribution distribution, double random) {
        double cumulative = 0.0;
        double[] probabilities = distribution.probabilities();
        for (int bucket = 0; bucket < probabilities.length; bucket++) {
            cumulative += probabilities[bucket];
            if (random < cumulative) return bucket;
        }
        return probabilities.length - 1;
    }

    private static RedCard sampleRedCard(TeamCompartmentAggregator.TeamAggregationResult team,
                                         ShooterMatchMechanic mechanic,
                                         SplittableRandom random) {
        if (!mechanic.redCard(team.pressing(), random.nextDouble())) return RedCard.none();
        List<TeamCompartmentAggregator.PlayerBreakdown> eligible = team.players().stream()
                .filter(player -> redCardEligible(player.slot().position()))
                .sorted(Comparator.comparing((TeamCompartmentAggregator.PlayerBreakdown player) ->
                                player.slot().position())
                        .thenComparingInt(player -> player.slot().occurrence())
                        .thenComparingLong(TeamCompartmentAggregator.PlayerBreakdown::playerId))
                .toList();
        if (eligible.isEmpty()) return RedCard.none();
        TeamCompartmentAggregator.PlayerBreakdown removed = eligible.get(random.nextInt(eligible.size()));
        return new RedCard(removed.playerId(), removed.finalAttackContribution(),
                removed.finalProtectionContribution() * team.exposure().protectionMultiplier());
    }

    private static boolean redCardEligible(PlayerPosition position) {
        return position != PlayerPosition.GK && position != PlayerPosition.ST;
    }

    private static EffectivePower effectivePower(TeamCompartmentAggregator.TeamAggregationResult team,
                                                 RedCard redCard) {
        return new EffectivePower(Math.max(0.0, team.attack() - redCard.attackRemoved()),
                Math.max(0.0, team.attackProtection() - redCard.protectionRemoved()));
    }

    private static ShooterGoals sampleShooterGoals(TeamCompartmentAggregator.TeamAggregationResult team,
                                                   RedCard ownRed,
                                                   String opponentPressing,
                                                   ShooterMatchMechanic mechanic,
                                                   SplittableRandom random) {
        TeamCompartmentAggregator.ShooterProfile shooter = team.shooter();
        if (shooter == null) return ShooterGoals.none();
        if (Objects.equals(ownRed.playerId(), shooter.playerId())) {
            return new ShooterGoals(shooter.playerId(), 0, 0);
        }
        int shots = mechanic.sampleShotCount(shooter.positioning(), random.nextDouble());
        double chance = mechanic.goalChance(shooter.longShots(), opponentPressing);
        int goals = 0;
        for (int shot = 0; shot < shots; shot++) {
            if (random.nextDouble() < chance) goals++;
        }
        return new ShooterGoals(shooter.playerId(), shots, goals);
    }

    private static int retainedGoals(int generatedGoals, double suppression, SplittableRandom random) {
        int retained = 0;
        double keepChance = 1.0 - suppression;
        for (int goal = 0; goal < generatedGoals; goal++) {
            if (random.nextDouble() < keepChance) retained++;
        }
        return retained;
    }

    private static PassingGoals samplePassingGoals(
            TeamCompartmentAggregator.TeamAggregationResult team,
            TeamCompartmentAggregator.TeamAggregationResult opponent,
            RedCard ownRed, RedCard opponentRed, double controlChance,
            PassingStyleMatchMechanic mechanic, SplittableRandom random) {
        TeamCompartmentAggregator.PassingStyleProfile profile = team.passingStyle();
        if (profile == null || !profile.active()) return PassingGoals.none();
        List<TeamCompartmentAggregator.PassingStriker> strikers = profile.strikers().stream()
                .filter(player -> !Objects.equals(player.playerId(), ownRed.playerId()))
                .sorted(Comparator.comparingLong(TeamCompartmentAggregator.PassingStriker::playerId))
                .toList();
        if (strikers.isEmpty()) return PassingGoals.none();
        TeamCompartmentAggregator.PassingStriker striker = strikers.get(random.nextInt(strikers.size()));
        int opportunities = mechanic.sampleOpportunityCount(random.nextDouble());
        int opponentPace20 = mechanic.pace20Midfielders(opponent.passingStyle(), opponentRed.playerId());
        double chance = mechanic.strikerGoalChance(controlChance, striker.finishing(), striker.pace(), opponentPace20);
        int goals = 0;
        for (int opportunity = 0; opportunity < opportunities; opportunity++) {
            if (random.nextDouble() < chance) goals++;
        }
        return new PassingGoals(striker.playerId(), opportunities, goals, opportunities * chance);
    }

    private static void validate(GoalProbabilityFormula.GoalDistribution distribution) {
        Objects.requireNonNull(distribution, "distribution");
        int cap = distribution.cap();
        double[] probabilities = distribution.probabilities();
        if (cap < 0 || probabilities.length != cap + 1) {
            throw new IllegalArgumentException("invalid goal PMF length/cap");
        }
        double sum = 0.0;
        for (double probability : probabilities) {
            if (!Double.isFinite(probability) || probability < 0.0) {
                throw new IllegalArgumentException("goal PMF must be finite and non-negative");
            }
            sum += probability;
        }
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("goal PMF must sum to 1.0");
        }
    }

    private record EffectivePower(double attack, double protection) {}

    private record RedCard(Long playerId, double attackRemoved, double protectionRemoved) {
        static RedCard none() { return new RedCard(null, 0.0, 0.0); }
    }

    private record ShooterGoals(Long playerId, int shots, int goals) {
        static ShooterGoals none() { return new ShooterGoals(null, 0, 0); }
    }

    private record PassingGoals(Long playerId, int opportunities, int goals, double expectedGoals) {
        static PassingGoals none() { return new PassingGoals(null, 0, 0, 0.0); }
    }

    public record GoalSample(int homeGoals, int awayGoals,
                             int homeCollectiveGoals, int awayCollectiveGoals,
                             Long homeRedCardPlayerId, Long awayRedCardPlayerId,
                             Long homeShooterPlayerId, Long awayShooterPlayerId,
                             int homeShooterGoals, int awayShooterGoals,
                             double homeEffectiveAttack, double homeEffectiveProtection,
                             double awayEffectiveAttack, double awayEffectiveProtection,
                             double homeXg, double awayXg,
                             int homeShooterShots, int awayShooterShots,
                             Long homePassingPlayerId, Long awayPassingPlayerId,
                             int homePassingGoals, int awayPassingGoals,
                             int homePassingOpportunities, int awayPassingOpportunities,
                             double homePassingControl, double awayPassingControl) {
        public GoalSample(int homeGoals, int awayGoals) {
            this(homeGoals, awayGoals, homeGoals, awayGoals,
                    null, null, null, null, 0, 0,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0,
                    null, null, 0, 0, 0, 0, 0.0, 0.0);
        }

        public GoalSample {
            if (homeGoals < 0 || awayGoals < 0 || homeCollectiveGoals < 0 || awayCollectiveGoals < 0
                    || homeShooterGoals < 0 || awayShooterGoals < 0
                    || homeShooterShots < 0 || awayShooterShots < 0
                    || homePassingGoals < 0 || awayPassingGoals < 0
                    || homePassingOpportunities < 0 || awayPassingOpportunities < 0) {
                throw new IllegalArgumentException("goal counts must be non-negative");
            }
            if (homeShooterGoals > homeShooterShots || awayShooterGoals > awayShooterShots) {
                throw new IllegalArgumentException("SHOOTER goals cannot exceed SHOOTER attempts");
            }
            if (homePassingGoals > homePassingOpportunities || awayPassingGoals > awayPassingOpportunities) {
                throw new IllegalArgumentException("PASSING STYLE goals cannot exceed opportunities");
            }
            if (homeGoals != homeCollectiveGoals + homeShooterGoals + homePassingGoals
                    || awayGoals != awayCollectiveGoals + awayShooterGoals + awayPassingGoals) {
                throw new IllegalArgumentException("total goals must equal collective plus individual goals");
            }
            if ((homeShooterShots > 0 && homeShooterPlayerId == null)
                    || (awayShooterShots > 0 && awayShooterPlayerId == null)) {
                throw new IllegalArgumentException("SHOOTER attempts require a SHOOTER player");
            }
            if ((homePassingOpportunities > 0 && homePassingPlayerId == null)
                    || (awayPassingOpportunities > 0 && awayPassingPlayerId == null)) {
                throw new IllegalArgumentException("PASSING STYLE opportunities require a striker");
            }
            if (!Double.isFinite(homePassingControl) || homePassingControl < 0.0 || homePassingControl > 1.0
                    || !Double.isFinite(awayPassingControl) || awayPassingControl < 0.0 || awayPassingControl > 1.0) {
                throw new IllegalArgumentException("PASSING STYLE control must be in [0,1]");
            }
        }
    }
}
