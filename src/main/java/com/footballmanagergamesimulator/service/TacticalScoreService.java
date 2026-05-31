package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
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

    /**
     * A starter's used (base) position and match value, plus the three squad-aptitude raw scores
     * (0..1) derived from his {@link PlayerSkills} — {@code pressing}, {@code discipline},
     * {@code stamina} (see {@link #playerAptitudes}). The legacy two-arg form (tests / synthetic
     * opponents with no skills) leaves the aptitudes {@code NaN} ⇒ {@link #profile} treats them as
     * the config baseline ⇒ neutral gating.
     */
    public record StarterValue(String usedPosition, double value,
                               double pressingRaw, double disciplineRaw, double staminaRaw) {
        /** Legacy two-arg form: no skill data ⇒ aptitudes NaN ⇒ baseline (neutral) in {@link #profile}. */
        public StarterValue(String usedPosition, double value) {
            this(usedPosition, value, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /**
     * A squad's attack and defense ratings (before tactic redistribution) plus the three
     * squad-aptitude <b>multipliers</b> (§19.7): {@code pressingMult} gates pressing disruption,
     * {@code disciplineMult} gates park-the-bus defensive control, {@code staminaMult} gates the
     * pressing fatigue cost (gegenpress). Each is centered at 1.0 (neutral); the two-arg form (panel
     * / synthetic opponents) is exactly neutral ⇒ identical to the pre-aptitude behavior.
     */
    public record TeamProfile(double attack, double defense,
                              double pressingMult, double disciplineMult, double staminaMult) {
        /** Neutral form (panel / synthetic opponents): all aptitude multipliers 1.0 ⇒ no gating. */
        public TeamProfile(double attack, double defense) {
            this(attack, defense, 1.0, 1.0, 1.0);
        }
    }

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
                raw.defense() * coachFactor(defensiveAbility, k),
                raw.pressingMult(), raw.disciplineMult(), raw.staminaMult());
    }

    private static double coachFactor(double ability, double strength) {
        double a = Math.max(0, Math.min(100, ability));
        return 1 + strength * (a - 50) / 50.0;
    }

    /**
     * Split a squad into attack/defense by position (config-driven {@code attackShare}) and derive the
     * three squad-aptitude multipliers from the starters' raw aptitudes (§19.7), value-weighted so the
     * key players count more. A starter with no skill data ({@code NaN} raw) contributes the config
     * baseline ⇒ neutral. The aggregated raw aptitude is mapped to a centered multiplier via
     * {@link #aptitudeMult}.
     */
    public TeamProfile profile(List<StarterValue> starters) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double baseline = cfg.getAptitudeBaseline();
        double attack = 0, defense = 0;
        double wSum = 0, pSum = 0, dSum = 0, sSum = 0;
        for (StarterValue s : starters) {
            double share = cfg.attackShareFor(s.usedPosition());
            attack += s.value() * share;
            defense += s.value() * (1.0 - share);
            double w = Math.max(0, s.value());
            wSum += w;
            pSum += w * (Double.isNaN(s.pressingRaw()) ? baseline : s.pressingRaw());
            dSum += w * (Double.isNaN(s.disciplineRaw()) ? baseline : s.disciplineRaw());
            sSum += w * (Double.isNaN(s.staminaRaw()) ? baseline : s.staminaRaw());
        }
        double pRaw = wSum > 0 ? pSum / wSum : baseline;
        double dRaw = wSum > 0 ? dSum / wSum : baseline;
        double sRaw = wSum > 0 ? sSum / wSum : baseline;
        return new TeamProfile(attack, defense,
                aptitudeMult(cfg, pRaw), aptitudeMult(cfg, dRaw), aptitudeMult(cfg, sRaw));
    }

    /** Centered aptitude multiplier: {@code clamp(1 + gate·(raw − baseline), multMin, multMax)}. */
    private static double aptitudeMult(MatchEngineConfig.TacticalModel cfg, double raw) {
        double m = 1 + cfg.getAptitudeGateStrength() * (raw - cfg.getAptitudeBaseline());
        return clamp(m, cfg.getAptitudeMultMin(), cfg.getAptitudeMultMax());
    }

    /**
     * Per-player squad-aptitude raw scores (0..1) from his {@link PlayerSkills} (1..20 attributes) and
     * current {@code fitness} (0..100), used to build {@link StarterValue}. The mapping (§19.7, agreed,
     * tunable): pressing ← Work Rate, Stamina, Aggression, Anticipation, Pace; discipline ←
     * Concentration, Positioning, Composure, Bravery, Teamwork, Decisions; stamina ← Stamina + Natural
     * Fitness (attribute) blended with current fitness. {@code null} skills ⇒ all {@code NaN} (baseline).
     */
    public static double[] playerAptitudes(PlayerSkills sk, double fitness) {
        if (sk == null) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double pressing = avg20(sk.getWorkRate(), sk.getStamina(), sk.getAggression(),
                sk.getAnticipation(), sk.getPace());
        double discipline = avg20(sk.getConcentration(), sk.getPositioning(), sk.getComposure(),
                sk.getBravery(), sk.getTeamwork(), sk.getDecisions());
        double staminaAttr = avg20(sk.getStamina(), sk.getNaturalFitness());
        double stamina = 0.6 * staminaAttr + 0.4 * (Math.max(0, Math.min(100, fitness)) / 100.0);
        return new double[]{pressing, discipline, stamina};
    }

    /** Average of 1..20 attributes normalized to 0..1. */
    private static double avg20(int... attrs) {
        double sum = 0;
        for (int a : attrs) sum += a;
        return (sum / attrs.length) / 20.0;
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
        double dac = cfg.getDirectnessAttackCost(), pbv = cfg.getPressBypassVulnerability();

        // Base attack/defense after mentality (bias trade-off) and control (defense up, own attack down).
        // Squad DISCIPLINE gates the defensive control gain: park-the-bus only pays off with a disciplined
        // XI (high Concentration / Positioning / Composure …); a poor one barely tightens up (§19.7).
        double effAtt1 = p1.attack() * (1 + bs * t1.attackBias()) * (1 - ca * t1.control());
        double effDef1 = p1.defense() * (1 - bs * t1.attackBias()) * (1 + cs * p1.disciplineMult() * t1.control());
        double effAtt2 = p2.attack() * (1 + bs * t2.attackBias()) * (1 - ca * t2.control());
        double effDef2 = p2.defense() * (1 - bs * t2.attackBias()) * (1 + cs * p2.disciplineMult() * t2.control());

        // Passing directness is a TRADE-OFF, not a free win: long/direct balls exploit a high line
        // (via lineVuln below) but sacrifice build-up precision, so they cost a little of your own attack.
        effAtt1 *= (1 - dac * t1.directness());
        effAtt2 *= (1 - dac * t2.directness());

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
        // Squad PRESSING aptitude (Work Rate / Stamina / Aggression / Anticipation / Pace) gates how much
        // a press disrupts; squad STAMINA aptitude (+ current fitness) gates the fatigue cost — a low-fit
        // gegenpress side pays more (≈ 2−staminaMult), eroding its own attack (§19.7).
        effAtt1 *= (1 - pd * p2.pressingMult() * t2.press()) * (1 - psc * (2 - p1.staminaMult()) * t1.press());
        effAtt2 *= (1 - pd * p1.pressingMult() * t1.press()) * (1 - psc * (2 - p2.staminaMult()) * t2.press());

        // Pressing is a TRADE-OFF: a high press leaves space behind, so a DIRECT opponent bypasses it —
        // your press RAISES their attack scaled by their directness. High press wins vs a possession
        // side but backfires against a direct one ⇒ no universally-best pressing level.
        effAtt2 *= (1 + pbv * t1.press() * t2.directness());
        effAtt1 *= (1 + pbv * t2.press() * t1.directness());

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

    /** Scale a profile's attack and defense by {@code s} (for building an opponent panel); the
     *  aptitude multipliers carry over unchanged (a scaled opponent has the same execution quality). */
    public TeamProfile scaled(TeamProfile p, double s) {
        return new TeamProfile(p.attack() * s, p.defense() * s,
                p.pressingMult(), p.disciplineMult(), p.staminaMult());
    }

    /** {xgHome, xgAway} for a matchup with NO home advantage (the fair-ranking expected goals). */
    public double[] expectedGoalsForRanking(TeamProfile mine, TacticVector myT, TeamProfile opp, TacticVector oppT) {
        return expectedGoals(mine, myT, opp, oppT, engineConfig.getTacticalModel(), false);
    }

    /**
     * Average {@link #expectedPoints} for {@code myT} across an opponent <b>panel</b> — {@code mine}
     * scaled to a weaker / equal / stronger opponent (config {@code opponentPanel}, default
     * {@code {0.7, 1.0, 1.3}}) — each playing a <b>distinct, non-neutral</b> tactic: the weak side parks
     * the bus (defensive / narrow / deep / low tempo), the equal side plays neutral, the strong side
     * attacks (high line / high press / wide / direct). Ranking against this SPREAD (not one blank
     * opponent) is what lets the matchup axes (width, defensive line, pressing, directness, …) take
     * DIFFERENT optimal values per squad — against a neutral opponent every team's best answer is the
     * same max-defensive setup, so those axes all peg to one extreme.
     */
    public double panelExpectedPoints(TeamProfile mine, TacticVector myT) {
        TacticVector[] oppT = panelOpponentVectors();
        double[] panel = engineConfig.getTacticalModel().getOpponentPanel();
        double sum = 0;
        for (int i = 0; i < panel.length; i++) {
            sum += expectedPoints(mine, myT, scaled(mine, panel[i]), oppT[i % oppT.length]);
        }
        return sum / panel.length;
    }

    /** The weak/equal/strong panel opponents' tactic vectors (built once from the live config). */
    private TacticVector[] panelOpponentVectors() {
        TacticVector[] v = panelOpponentVectors;
        if (v == null) {
            v = new TacticVector[]{
                    vector(PANEL_OPP_WEAK),            // weak side parks the bus
                    vector(new PersonalizedTactic()),  // equal side plays neutral
                    vector(PANEL_OPP_STRONG)           // strong side attacks
            };
            panelOpponentVectors = v;
        }
        return v;
    }

    private TacticVector[] panelOpponentVectors;

    private static final PersonalizedTactic PANEL_OPP_WEAK = panelOpponent(
            "Defensive", "Lower", "Short", "Keep Ball", "Frequently", "Deep", "Low", "Narrow");
    private static final PersonalizedTactic PANEL_OPP_STRONG = panelOpponent(
            "Attacking", "Higher", "Long", "Free Ball Early", "Never", "High", "High", "Wide");

    private static PersonalizedTactic panelOpponent(String mentality, String tempo, String passing,
            String inPossession, String timeWasting, String defLine, String pressing, String width) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(mentality);
        t.setTempo(tempo);
        t.setPassingType(passing);
        t.setInPossession(inPossession);
        t.setTimeWasting(timeWasting);
        t.setDefensiveLine(defLine);
        t.setPressing(pressing);
        t.setWidth(width);
        return t;
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
