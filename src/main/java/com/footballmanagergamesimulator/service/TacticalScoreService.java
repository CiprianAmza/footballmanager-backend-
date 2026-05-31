package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * Two-axis tactical match model (trade-off + matchup). A squad's value is split into an
 * <b>attack</b> and a <b>defense</b> rating by the position of each starter; tactic settings then
 * <b>redistribute</b> between them (push to attack ⇒ less defense — a trade-off, not a free bonus)
 * and open/slow the game. Goals come from each side's effective <b>attack vs the other's
 * defense</b> (matchup), so there is no universally-best tactic — what beats an attacking side can
 * lose to a defensive one.
 *
 * <p>This is the foundation for richer AI tactics: {@link #expectedGoalDifference} gives a cheap,
 * deterministic quality score used to rank candidate tactics for a squad.
 */
@Service
public class TacticalScoreService {

    @Autowired MatchEngineConfig engineConfig;

    /** Field RNG so determinism tests can inject a seeded {@link Random}; defaults to unseeded. */
    private Random random = new Random();

    /** Test seam: swap the RNG so the same seed reproduces the same scorelines. */
    public void setRandomForTesting(Random random) { this.random = random; }

    /** A starter's used (base) position and match value, for splitting a squad into attack/defense. */
    public record StarterValue(String usedPosition, double value) {}

    /** A squad's attack and defense ratings (before tactic redistribution). */
    public record TeamProfile(double attack, double defense) {}

    /** A tactic reduced to three numeric axes: attack bias, openness risk, defensive control. */
    public record TacticVector(double attackBias, double risk, double control) {}

    /**
     * The intermediate decomposition of a matchup: each side's <b>effective</b> attack and defense
     * (squad profile after tactic redistribution + control) plus the game's {@code openness} (goal
     * scale). Exposed so other engines (live minute-by-minute, extra time) can drive their chances
     * from the same attack-vs-defense matchup instead of a symmetric scalar power ratio.
     */
    public record Matchup(double effAtt1, double effDef1, double effAtt2, double effDef2, double openness) {}

    /**
     * Apply the manager's coaching to a raw squad profile: the offensive ability amplifies attack,
     * the defensive ability amplifies defense (each ±{@code coachStrength} at the 0/100 extremes,
     * neutral at 50). A lopsided coach makes his team strong on one side and shapes which tactic
     * suits him.
     */
    public TeamProfile coachedProfile(TeamProfile raw, double offensiveAbility, double defensiveAbility) {
        double k = engineConfig.getTacticalModel().getCoachStrength();
        return new TeamProfile(
                raw.attack() * coachFactor(offensiveAbility, k),
                raw.defense() * coachFactor(defensiveAbility, k));
    }

    private static double coachFactor(double ability, double strength) {
        double a = Math.max(0, Math.min(100, ability));
        return 1 + strength * (a - 50) / 50.0;
    }

    /** Split a squad into attack/defense by position (config-driven {@code attackShare}). */
    public TeamProfile profile(List<StarterValue> starters) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double attack = 0, defense = 0;
        for (StarterValue s : starters) {
            double share = cfg.attackShareFor(s.usedPosition());
            attack += s.value() * share;
            defense += s.value() * (1.0 - share);
        }
        return new TeamProfile(attack, defense);
    }

    /** Reduce a {@link PersonalizedTactic} to its numeric axes. Neutral (all-default) ⇒ all zero. */
    public TacticVector vector(PersonalizedTactic t) {
        String mentality = orDefault(t.getMentality(), "Balanced");
        String tempo = orDefault(t.getTempo(), "Standard");
        String passing = orDefault(t.getPassingType(), "Normal");
        String possession = orDefault(t.getInPossession(), "Standard");
        String timeWasting = orDefault(t.getTimeWasting(), "Sometimes");

        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double bias = cfg.mentalityBias(mentality) + cfg.possessionBias(possession);
        double risk = cfg.tempoRisk(tempo) + cfg.passingRisk(passing);
        double control = cfg.possessionControl(possession) + cfg.timeWastingControl(timeWasting);

        return new TacticVector(
                clamp(bias, -1.2, 1.5),
                clamp(risk, -1.5, 1.5),
                clamp(control, 0.0, 1.5));
    }

    /** Sample a scoreline for a matchup using the field RNG (production path). team1 is home. */
    public List<Integer> score(TeamProfile p1, TacticVector t1, TeamProfile p2, TacticVector t2) {
        return score(p1, t1, p2, t2, random);
    }

    /** Sample a scoreline for a matchup. team1 is the home side. */
    public List<Integer> score(TeamProfile p1, TacticVector t1, TeamProfile p2, TacticVector t2, Random rng) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double[] xg = expectedGoals(p1, t1, p2, t2, cfg, true);
        int g1 = poisson(rng, xg[0], cfg.getMaxGoalsPerTeam());
        int g2 = poisson(rng, xg[1], cfg.getMaxGoalsPerTeam());
        return List.of(g1, g2);
    }

    /**
     * Sample a knockout <b>extra-time</b> mini-match (no home advantage, far fewer goals than a full
     * 90'). Same attack-vs-defense matchup as {@link #score}, with {@code openness} scaled down by
     * {@code tacticalModel.extraTimeOpennessScale}. Used by {@code KnockoutTieResolver} so the
     * tiebreak runs on the two-axis model, not the scalar engine.
     */
    public List<Integer> scoreExtraTime(TeamProfile p1, TacticVector t1, TeamProfile p2, TacticVector t2, Random rng) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double scale = cfg.getExtraTimeOpennessScale();
        double[] xg = expectedGoals(p1, t1, p2, t2, cfg, false);
        int g1 = poisson(rng, xg[0] * scale, cfg.getMaxGoalsPerTeam());
        int g2 = poisson(rng, xg[1] * scale, cfg.getMaxGoalsPerTeam());
        return List.of(g1, g2);
    }

    /**
     * The attack/defense decomposition of a matchup (before sampling goals), for consumers that need
     * the effective ratings + openness directly (live minute-by-minute engine, extra time).
     */
    public Matchup matchup(TeamProfile p1, TacticVector t1, TeamProfile p2, TacticVector t2) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double effAtt1 = Math.max(1e-6, p1.attack() * (1 + cfg.getBiasStrength() * t1.attackBias()));
        double effDef1 = Math.max(1e-6, p1.defense() * (1 - cfg.getBiasStrength() * t1.attackBias())
                * (1 + cfg.getControlStrength() * t1.control()));
        double effAtt2 = Math.max(1e-6, p2.attack() * (1 + cfg.getBiasStrength() * t2.attackBias()));
        double effDef2 = Math.max(1e-6, p2.defense() * (1 - cfg.getBiasStrength() * t2.attackBias())
                * (1 + cfg.getControlStrength() * t2.control()));
        double openness = cfg.getBaseOpenness()
                * (1 + cfg.getOpennessStrength() * (t1.risk() + t2.risk()) / 2.0)
                * (1 - cfg.getControlOpennessStrength() * (t1.control() + t2.control()) / 2.0);
        openness = Math.max(0.2, openness);
        return new Matchup(effAtt1, effDef1, effAtt2, effDef2, openness);
    }

    /**
     * Deterministic quality of {@code myTactic} for a squad against a representative opponent:
     * expected goal difference (my xG − their xG). Higher = better. Used to rank candidate tactics
     * cheaply (no simulation), so an AI manager can pick a tactic at a rank set by his skill.
     */
    public double expectedGoalDifference(TeamProfile mine, TacticVector myTactic,
                                         TeamProfile opponent, TacticVector opponentTactic) {
        double[] xg = expectedGoals(mine, myTactic, opponent, opponentTactic,
                engineConfig.getTacticalModel(), false);
        return xg[0] - xg[1];
    }

    // ==================== internals ====================

    /** Returns {xgHome, xgAway}. {@code homeAdvantage} applies the home attack bonus to side 1. */
    private double[] expectedGoals(TeamProfile p1, TacticVector t1, TeamProfile p2, TacticVector t2,
                                   MatchEngineConfig.TacticalModel cfg, boolean homeAdvantage) {
        Matchup m = matchup(p1, t1, p2, t2);

        // Amplify the attack-vs-defense gap so stronger squads dominate more (squad value decisive).
        double exp = cfg.getRatioExponent();
        double a1 = Math.pow(m.effAtt1(), exp), d2 = Math.pow(m.effDef2(), exp);
        double a2 = Math.pow(m.effAtt2(), exp), d1 = Math.pow(m.effDef1(), exp);
        double ratio1 = a1 / (a1 + d2);
        double ratio2 = a2 / (a2 + d1);

        double xg1 = m.openness() * ratio1 * (homeAdvantage ? 1 + cfg.getHomeAttackBonus() : 1);
        double xg2 = m.openness() * ratio2;
        return new double[]{xg1, xg2};
    }

    private static int poisson(Random random, double expectedGoals, int maxGoals) {
        double l = Math.exp(-expectedGoals);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return Math.min(k - 1, maxGoals);
    }

    private static String orDefault(String v, String def) { return v == null || v.isBlank() ? def : v; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
