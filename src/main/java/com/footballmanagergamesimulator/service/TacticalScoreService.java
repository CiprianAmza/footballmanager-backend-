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

    /**
     * A tactic reduced to numeric axes. The first three are the original ones (attack bias, openness
     * risk, defensive control); the Strat-2 axes add opponent-dependent counters:
     * {@code directness} (passing directness 0..1, the thing a high line is punished by),
     * {@code line} (defensive line height −1..1), {@code press} (pressing 0..1), {@code width}
     * (narrow −1 .. wide +1). All Strat-2 axes are 0 at neutral ⇒ a default tactic is a no-op. */
    public record TacticVector(double attackBias, double risk, double control,
                               double directness, double line, double press, double width) {
        /** Legacy three-axis constructor: Strat-2 axes neutral (zero). */
        public TacticVector(double attackBias, double risk, double control) {
            this(attackBias, risk, control, 0, 0, 0, 0);
        }
    }

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
        String line = orDefault(t.getDefensiveLine(), "Standard");
        String press = orDefault(t.getPressing(), "Low");
        String width = orDefault(t.getWidth(), "Balanced");
        // Faza 2 team-level instructions (neutral token ⇒ 0 contribution ⇒ no-op).
        String dribbling = orDefault(t.getDribbling(), "Standard");
        String foulFrequency = orDefault(t.getFoulFrequency(), "Normal");
        String foulHardness = orDefault(t.getFoulHardness(), "Medium");
        String fragmentation = orDefault(t.getTempoFragmentation(), "Normal");
        String widePlay = orDefault(t.getWidePlay(), "Shoot");
        String transition = orDefault(t.getTransition(), "Balanced");

        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double bias = cfg.mentalityBias(mentality) + cfg.possessionBias(possession);
        double risk = cfg.tempoRisk(tempo) + cfg.passingRisk(passing);
        double control = cfg.possessionControl(possession) + cfg.timeWastingControl(timeWasting);
        double directness = cfg.passingDirectness(passing);
        double lineAxis = cfg.lineHeightAxis(line);
        double pressAxis = cfg.pressAxis(press);
        double widthAxis = cfg.widthAxis(width);

        // Faza 2: fold team-level instructions into the EXISTING axes (neutral ⇒ +0 ⇒ exact no-op).
        risk += cfg.dribblingRisk(dribbling) + cfg.widePlayRisk(widePlay) + cfg.transitionRisk(transition);
        control += cfg.foulControl(foulFrequency) + cfg.foulHardnessControl(foulHardness)
                + cfg.fragmentationControl(fragmentation) + cfg.transitionControl(transition);
        widthAxis += cfg.widePlayWidth(widePlay);

        return new TacticVector(
                clamp(bias, -1.2, 1.5),
                clamp(risk, -1.5, 1.5),
                clamp(control, 0.0, 1.5),
                clamp(directness, 0.0, 1.0),
                clamp(lineAxis, -1.0, 1.0),
                clamp(pressAxis, 0.0, 1.0),
                clamp(widthAxis, -1.0, 1.0));
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
        double bs = cfg.getBiasStrength(), cs = cfg.getControlStrength(), ca = cfg.getControlAttackCost();
        double lhs = cfg.getLineHeightSupport(), lhv = cfg.getLineHeightVulnerability();
        double pd = cfg.getPressDisruption(), psc = cfg.getPressStaminaCost(), plc = cfg.getPressLineCompound();
        double ws = cfg.getWidthStrength();

        // Base attack/defense after mentality (bias trade-off) and control (defense up, own attack down).
        double effAtt1 = p1.attack() * (1 + bs * t1.attackBias()) * (1 - ca * t1.control());
        double effDef1 = p1.defense() * (1 - bs * t1.attackBias()) * (1 + cs * t1.control());
        double effAtt2 = p2.attack() * (1 + bs * t2.attackBias()) * (1 - ca * t2.control());
        double effDef2 = p2.defense() * (1 - bs * t2.attackBias()) * (1 + cs * t2.control());

        // Defensive line: a high line supports own attack a touch (lhs·line) but is punished by a
        // DIRECT opponent (space behind) — amplified by your own pressing (space ahead of a high line).
        double hi1 = Math.max(0, t1.line()), hi2 = Math.max(0, t2.line());
        double lineVuln1 = 1 + (lhv + plc * t1.press()) * hi1 * t2.directness();
        double lineVuln2 = 1 + (lhv + plc * t2.press()) * hi2 * t1.directness();
        effDef1 /= lineVuln1;
        effDef2 /= lineVuln2;
        effAtt1 *= (1 + lhs * t1.line());
        effAtt2 *= (1 + lhs * t2.line());

        // Pressing: disrupt the opponent's attack (pd·theirPress on your attack), pay a small own-attack
        // fatigue proxy on the instant engine (full stamina coupling lives in the live engine).
        effAtt1 *= (1 - pd * t2.press()) * (1 - psc * t1.press());
        effAtt2 *= (1 - pd * t1.press()) * (1 - psc * t2.press());

        // Width rock-paper-scissors: wide attack beats a narrow defense and vice versa (no universal best).
        effAtt1 *= (1 + ws * t1.width() * (-t2.width()));
        effAtt2 *= (1 + ws * t2.width() * (-t1.width()));

        effAtt1 = Math.max(1e-6, effAtt1);
        effDef1 = Math.max(1e-6, effDef1);
        effAtt2 = Math.max(1e-6, effAtt2);
        effDef2 = Math.max(1e-6, effDef2);

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

    /**
     * Exact win/draw/loss probabilities for a matchup with the given expected goals, via a Poisson
     * <b>grid</b> over {@code 0..maxGoals} for each side (no Monte Carlo). Each side's truncated
     * Poisson PMF folds its tail mass into the {@code maxGoals} cell (matching the capped sampler in
     * {@link #poisson}), so each PMF sums to 1; the 2D grid is then summed into
     * {@code [P(home win), P(draw), P(home loss)]}.
     */
    public double[] outcomeProbabilities(double xgHome, double xgAway) {
        int max = engineConfig.getTacticalModel().getMaxGoalsPerTeam();
        double[] ph = truncatedPoissonPmf(xgHome, max);
        double[] pa = truncatedPoissonPmf(xgAway, max);
        double win = 0, draw = 0, loss = 0;
        for (int h = 0; h <= max; h++) {
            for (int a = 0; a <= max; a++) {
                double cell = ph[h] * pa[a];
                if (h > a) win += cell;
                else if (h == a) draw += cell;
                else loss += cell;
            }
        }
        return new double[]{win, draw, loss};
    }

    /**
     * Expected league points (3·win + 1·draw) for {@code myT} against {@code oppT}, evaluated with
     * <b>no</b> home advantage so tactic ranking is fair. A variance-aware metric (unlike
     * {@link #expectedGoalDifference}'s mean), so it captures the value of closed/defensive tactics
     * for underdogs that mean-xGD misses.
     */
    public double expectedPoints(TeamProfile mine, TacticVector myT, TeamProfile opp, TacticVector oppT) {
        double[] xg = expectedGoals(mine, myT, opp, oppT, engineConfig.getTacticalModel(), false);
        double[] p = outcomeProbabilities(xg[0], xg[1]);
        return 3 * p[0] + p[1];
    }

    /** Scale a profile's attack and defense by {@code s} (for building an opponent panel). */
    public TeamProfile scaled(TeamProfile p, double s) {
        return new TeamProfile(p.attack() * s, p.defense() * s);
    }

    /** {xgHome, xgAway} for a matchup with NO home advantage (the fair-ranking expected goals). */
    public double[] expectedGoalsForRanking(TeamProfile mine, TacticVector myT, TeamProfile opp, TacticVector oppT) {
        return expectedGoals(mine, myT, opp, oppT, engineConfig.getTacticalModel(), false);
    }

    /**
     * Average {@link #expectedPoints} for {@code myT} across an opponent <b>panel</b> — {@code mine}
     * scaled to a weaker / equal / stronger opponent (config {@code opponentPanel}, default
     * {@code {0.7, 1.0, 1.3}}) — each playing a neutral tactic. Replaces self-mirror xGD as the
     * tactic-ranking metric: against a non-mirror panel the openness axes (tempo, passing) no longer
     * cancel, so the full tactic landscape differentiates and coaching skill becomes meaningful.
     */
    public double panelExpectedPoints(TeamProfile mine, TacticVector myT) {
        TacticVector neutral = vector(new PersonalizedTactic());
        double[] panel = engineConfig.getTacticalModel().getOpponentPanel();
        double sum = 0;
        for (double s : panel) {
            sum += expectedPoints(mine, myT, scaled(mine, s), neutral);
        }
        return sum / panel.length;
    }

    // ==================== internals ====================

    /** Truncated Poisson PMF over {@code 0..maxGoals}; tail mass folded into the last cell (sums to 1). */
    private static double[] truncatedPoissonPmf(double lambda, int maxGoals) {
        double[] pmf = new double[maxGoals + 1];
        double term = Math.exp(-lambda); // P(0)
        double cumulative = 0;
        for (int k = 0; k < maxGoals; k++) {
            pmf[k] = term;
            cumulative += term;
            term *= lambda / (k + 1); // P(k+1) = P(k) · λ/(k+1)
        }
        pmf[maxGoals] = Math.max(0.0, 1.0 - cumulative); // fold the tail into the capped cell
        return pmf;
    }

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
