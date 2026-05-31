package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.StarterValue;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * THROWAWAY quantitative probe (not for merge). Computes the flatness / sensitivity / variance
 * numbers for the Best-Tactic landscape using the real {@link TacticalScoreService} math with a
 * synthetic balanced 442 squad of magnitude comparable to Desert Lion (squad value ~1292).
 * Plain unit test, same package so it can set the package-private engineConfig field; no Spring.
 */
class TacticLandscapeProbeTest {

    static final List<String> MENTALITIES = List.of("Very Attacking", "Attacking", "Balanced", "Defensive", "Very Defensive");
    static final List<String> TIME_WASTING = List.of("Never", "Sometimes", "Frequently", "Always");
    static final List<String> IN_POSSESSION = List.of("Standard", "Keep Ball", "Free Ball Early");
    static final List<String> PASSING = List.of("Short", "Normal", "Long");
    static final List<String> TEMPO = List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    static TacticalScoreService svc() {
        TacticalScoreService s = new TacticalScoreService();
        s.engineConfig = new MatchEngineConfig(); // defaults = shipped values
        return s;
    }

    static List<PersonalizedTactic> all900() {
        List<PersonalizedTactic> out = new ArrayList<>();
        for (String m : MENTALITIES)
            for (String tw : TIME_WASTING)
                for (String ip : IN_POSSESSION)
                    for (String p : PASSING)
                        for (String te : TEMPO) {
                            PersonalizedTactic t = new PersonalizedTactic();
                            t.setMentality(m); t.setTimeWasting(tw); t.setInPossession(ip);
                            t.setPassingType(p); t.setTempo(te);
                            out.add(t);
                        }
        return out;
    }

    /** Balanced 442 squad whose values sum to ~1292 (Desert Lion magnitude). */
    static TeamProfile baseProfile(TacticalScoreService s, double scale) {
        // 442: GK, DR DC DC DL, MR MC MC ML, ST ST. Even ~117.5 each => ~1292 total.
        double v = 117.5 * scale;
        List<StarterValue> xi = List.of(
                new StarterValue("GK", v),
                new StarterValue("DR", v), new StarterValue("DC", v), new StarterValue("DC", v), new StarterValue("DL", v),
                new StarterValue("MR", v), new StarterValue("MC", v), new StarterValue("MC", v), new StarterValue("ML", v),
                new StarterValue("ST", v), new StarterValue("ST", v));
        return s.coachedProfile(s.profile(xi), 50.0, 50.0); // neutral coach
    }

    static double[] stats(double[] x) {
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY, sum = 0;
        for (double d : x) { best = Math.max(best, d); worst = Math.min(worst, d); sum += d; }
        double mean = sum / x.length, var = 0;
        for (double d : x) var += (d - mean) * (d - mean);
        double sd = Math.sqrt(var / x.length);
        return new double[]{best, worst, best - worst, sd, mean};
    }

    @Test
    void probe() {
        TacticalScoreService s = svc();
        TacticVector neutral = s.vector(new PersonalizedTactic());
        TeamProfile me = baseProfile(s, 1.0);
        List<PersonalizedTactic> tactics = all900();

        System.out.println("\n========== TACTIC LANDSCAPE PROBE ==========");
        System.out.printf("Synthetic balanced 442. me.attack=%.2f me.defense=%.2f total=%.2f%n",
                me.attack(), me.defense(), me.attack() + me.defense());

        // ---- 1. Flatness vs MIRROR ----
        double[] egd = new double[900];
        int bi = 0; double bv = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 900; i++) {
            egd[i] = s.expectedGoalDifference(me, s.vector(tactics.get(i)), me, neutral);
            if (egd[i] > bv) { bv = egd[i]; bi = i; }
        }
        double[] st = stats(egd);
        int w01 = 0, w05 = 0;
        for (double d : egd) { if (st[0] - d <= 0.01) w01++; if (st[0] - d <= 0.05) w05++; }
        System.out.println("\n--- 1. FLATNESS vs MIRROR (xGD, opp=neutral) ---");
        System.out.printf("best=%.4f worst=%.4f range=%.4f std=%.4f within0.01=%d within0.05=%d%n",
                st[0], st[1], st[2], st[3], w01, w05);
        PersonalizedTactic b = tactics.get(bi);
        System.out.printf("BEST tactic: mentality=%s tempo=%s passing=%s possession=%s timeWasting=%s%n",
                b.getMentality(), b.getTempo(), b.getPassingType(), b.getInPossession(), b.getTimeWasting());
        // converge check: settings of all tactics within 0.01 of best
        System.out.println("Settings of the top tactics within 0.01 of best:");
        int shown = 0;
        for (int i = 0; i < 900 && shown < 8; i++) {
            if (st[0] - egd[i] <= 0.01) {
                PersonalizedTactic t = tactics.get(i);
                System.out.printf("  xGD=%.4f  M=%s Te=%s Pa=%s Po=%s TW=%s%n", egd[i],
                        t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(), t.getTimeWasting());
                shown++;
            }
        }

        // ---- 2. Per-axis sensitivity (others held at baseline Balanced/Standard/Standard/Normal/Sometimes) ----
        System.out.println("\n--- 2. PER-AXIS SENSITIVITY (vary one axis, others fixed at baseline) ---");
        System.out.println("baseline: mentality=Balanced tempo=Standard passing=Normal possession=Standard timeWasting=Sometimes");
        axisDelta(s, me, neutral, "mentality", MENTALITIES);
        axisDelta(s, me, neutral, "tempo", TEMPO);
        axisDelta(s, me, neutral, "passing", PASSING);
        axisDelta(s, me, neutral, "possession", IN_POSSESSION);
        axisDelta(s, me, neutral, "timeWasting", TIME_WASTING);

        System.out.println("  [same axes vs STRONGER (+30%) opponent, where risk no longer cancels]");
        TeamProfile strongAxis = baseProfile(s, 1.30);
        axisDelta2(s, me, strongAxis, neutral, "mentality", MENTALITIES);
        axisDelta2(s, me, strongAxis, neutral, "tempo", TEMPO);
        axisDelta2(s, me, strongAxis, neutral, "passing", PASSING);
        axisDelta2(s, me, strongAxis, neutral, "possession", IN_POSSESSION);
        axisDelta2(s, me, strongAxis, neutral, "timeWasting", TIME_WASTING);

        // ---- 3. Mirror vs REAL opponents ----
        System.out.println("\n--- 3. SPREAD vs REAL OPPONENTS (range / std over 900) ---");
        spreadVs(s, me, neutral, tactics, "MIRROR (=me)", me);
        spreadVs(s, me, neutral, tactics, "STRONGER (+30%)", baseProfile(s, 1.30));
        spreadVs(s, me, neutral, tactics, "WEAKER (-30%)", baseProfile(s, 0.70));

        // ---- 4. KEY TEST: mean vs variance, underdog vs stronger opponent ----
        System.out.println("\n--- 4. KEY TEST: underdog (me) vs STRONGER opponent (+30%), mean xGD vs simulated win/draw ---");
        TeamProfile strong = baseProfile(s, 1.30);
        // opponent plays a neutral Balanced tactic
        TacticVector oppT = neutral;

        PersonalizedTactic attacking = new PersonalizedTactic();
        attacking.setMentality("Very Attacking"); attacking.setTempo("Much Higher");
        attacking.setPassingType("Long"); attacking.setInPossession("Free Ball Early"); attacking.setTimeWasting("Never");

        PersonalizedTactic defensive = new PersonalizedTactic();
        defensive.setMentality("Very Defensive"); defensive.setTempo("Much Lower");
        defensive.setPassingType("Short"); defensive.setInPossession("Keep Ball"); defensive.setTimeWasting("Always");

        evalTactic(s, me, strong, oppT, attacking, "ULTRA-ATTACKING / high-openness");
        evalTactic(s, me, strong, oppT, defensive, "ULTRA-DEFENSIVE / low-openness (max control)");

        System.out.println("========== END PROBE ==========\n");
    }

    static void axisDelta(TacticalScoreService s, TeamProfile me, TacticVector neutral, String axis, List<String> opts) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        StringBuilder sb = new StringBuilder();
        for (String o : opts) {
            PersonalizedTactic t = new PersonalizedTactic();
            t.setMentality("Balanced"); t.setTempo("Standard"); t.setPassingType("Normal");
            t.setInPossession("Standard"); t.setTimeWasting("Sometimes");
            switch (axis) {
                case "mentality" -> t.setMentality(o);
                case "tempo" -> t.setTempo(o);
                case "passing" -> t.setPassingType(o);
                case "possession" -> t.setInPossession(o);
                case "timeWasting" -> t.setTimeWasting(o);
            }
            double e = s.expectedGoalDifference(me, s.vector(t), me, neutral);
            min = Math.min(min, e); max = Math.max(max, e);
            sb.append(String.format("%s=%.4f ", o, e));
        }
        System.out.printf("  %-12s Δ=%.4f   [%s]%n", axis, max - min, sb.toString().trim());
    }

    static void axisDelta2(TacticalScoreService s, TeamProfile me, TeamProfile opp, TacticVector neutral, String axis, List<String> opts) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (String o : opts) {
            PersonalizedTactic t = new PersonalizedTactic();
            t.setMentality("Balanced"); t.setTempo("Standard"); t.setPassingType("Normal");
            t.setInPossession("Standard"); t.setTimeWasting("Sometimes");
            switch (axis) {
                case "mentality" -> t.setMentality(o);
                case "tempo" -> t.setTempo(o);
                case "passing" -> t.setPassingType(o);
                case "possession" -> t.setInPossession(o);
                case "timeWasting" -> t.setTimeWasting(o);
            }
            double e = s.expectedGoalDifference(me, s.vector(t), opp, neutral);
            min = Math.min(min, e); max = Math.max(max, e);
        }
        System.out.printf("    %-12s Δ=%.4f%n", axis, max - min);
    }

    static void spreadVs(TacticalScoreService s, TeamProfile me, TacticVector neutral,
                         List<PersonalizedTactic> tactics, String label, TeamProfile opp) {
        double[] e = new double[900];
        for (int i = 0; i < 900; i++) e[i] = s.expectedGoalDifference(me, s.vector(tactics.get(i)), opp, neutral);
        double[] st = stats(e);
        System.out.printf("  %-18s best=%.4f worst=%.4f range=%.4f std=%.4f mean=%.4f%n",
                label, st[0], st[1], st[2], st[3], st[4]);
    }

    static void evalTactic(TacticalScoreService s, TeamProfile me, TeamProfile opp, TacticVector oppT,
                           PersonalizedTactic t, String label) {
        TacticVector v = s.vector(t);
        double meanXgd = s.expectedGoalDifference(me, v, opp, oppT);
        Random rng = new Random(424242L);
        int N = 5000, win = 0, draw = 0, loss = 0;
        for (int i = 0; i < N; i++) {
            List<Integer> sc = s.score(me, v, opp, oppT, rng); // me is home
            if (sc.get(0) > sc.get(1)) win++;
            else if (sc.get(0).equals(sc.get(1))) draw++;
            else loss++;
        }
        System.out.printf("  %-44s vec(bias=%.2f risk=%.2f ctrl=%.2f)%n", label, v.attackBias(), v.risk(), v.control());
        System.out.printf("      mean xGD=%.4f | sim N=%d  win=%.1f%% draw=%.1f%% loss=%.1f%%  win+draw=%.1f%%%n",
                meanXgd, N, 100.0 * win / N, 100.0 * draw / N, 100.0 * loss / N, 100.0 * (win + draw) / N);
    }
}
