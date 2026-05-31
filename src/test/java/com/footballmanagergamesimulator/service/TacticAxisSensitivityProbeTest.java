package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig.TacticalModel;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * THROWAWAY analysis (not a real assertion test): quantifies how much each tactic axis actually moves
 * a WEAK team's expected season points against a varied opponent panel, and how that changes if the
 * dominant weights are reduced. Shows greedy marginal contributions (→ diminishing returns / what a
 * cap would free). Prints a report to stdout.
 */
class TacticAxisSensitivityProbeTest {

    private MatchEngineConfig cfg;
    private TacticalScoreService tss;
    private ManagerTacticService mts;
    private TeamProfile team;
    private TeamProfile[] oppProf;
    private TacticVector[] oppTac;

    private void setUp() {
        cfg = new MatchEngineConfig();
        tss = new TacticalScoreService();
        tss.engineConfig = cfg;
        mts = new ManagerTacticService();
        mts.tacticalScoreService = tss;
        mts.engineConfig = cfg;

        // Weak team (below the league average) — like Desert Lion.
        team = new TeamProfile(200, 200);
        setUpOpponentsOnly();
    }

    /** (Re)build the 19 opponents + their chosen tactics under the CURRENT cfg/tss/mts. */
    private void setUpOpponentsOnly() {
        int n = 19;
        oppProf = new TeamProfile[n];
        oppTac = new TacticVector[n];
        double avgAtt = 200, avgDef = 200;
        for (int i = 0; i < n; i++) {
            double v = 180 + i * 8; // spread 180..324 (avg ~252 ⇒ team below average)
            oppProf[i] = new TeamProfile(v, v);
            avgAtt += v; avgDef += v;
        }
        TeamProfile avg = new TeamProfile(avgAtt / (n + 1), avgDef / (n + 1));
        for (int i = 0; i < n; i++) {
            double ability = 45 + (i % 6) * 9; // 45..90
            oppTac[i] = tss.vector(mts.chooseTactic(oppProf[i], avg, ability));
        }
    }

    /** Total expected season points (double round-robin, no home advantage) for a team tactic. */
    private double seasonPoints(PersonalizedTactic t) {
        TacticVector v = tss.vector(t);
        double pts = 0;
        for (int o = 0; o < oppProf.length; o++) pts += 2 * tss.expectedPoints(team, v, oppProf[o], oppTac[o]);
        return pts;
    }

    private record Axis(String name, List<String> options, BiConsumer<PersonalizedTactic, String> setter) {}

    private static final List<Axis> AXES = List.of(
            new Axis("Mentality", TacticalModel.MENTALITY_OPTIONS, PersonalizedTactic::setMentality),
            new Axis("Tempo", TacticalModel.TEMPO_OPTIONS, PersonalizedTactic::setTempo),
            new Axis("Passing", TacticalModel.PASSING_OPTIONS, PersonalizedTactic::setPassingType),
            new Axis("In Possession", TacticalModel.IN_POSSESSION_OPTIONS, PersonalizedTactic::setInPossession),
            new Axis("Time Wasting", TacticalModel.TIME_WASTING_OPTIONS, PersonalizedTactic::setTimeWasting),
            new Axis("Def Line", TacticalModel.DEFENSIVE_LINE_OPTIONS, PersonalizedTactic::setDefensiveLine),
            new Axis("Pressing", TacticalModel.PRESSING_OPTIONS, PersonalizedTactic::setPressing),
            new Axis("Width", TacticalModel.WIDTH_OPTIONS, PersonalizedTactic::setWidth),
            new Axis("Dribbling", TacticalModel.DRIBBLING_OPTIONS, PersonalizedTactic::setDribbling),
            new Axis("Fouls", TacticalModel.FOUL_FREQUENCY_OPTIONS, PersonalizedTactic::setFoulFrequency),
            new Axis("Foul Hard", TacticalModel.FOUL_HARDNESS_OPTIONS, PersonalizedTactic::setFoulHardness),
            new Axis("Tempo Frag", TacticalModel.TEMPO_FRAGMENTATION_OPTIONS, PersonalizedTactic::setTempoFragmentation),
            new Axis("Wide Play", TacticalModel.WIDE_PLAY_OPTIONS, PersonalizedTactic::setWidePlay),
            new Axis("Transition", TacticalModel.TRANSITION_OPTIONS, PersonalizedTactic::setTransition));

    @Test
    void report() {
        setUp();
        // GOAL: in the top-10 DISTINCT advisor tactics, no 4 (ideally no 3) axes should share a common
        // value. Measure how many axes are CONSTANT across the top-10 under the current (real) config
        // and under an ENRICHED enumeration (multi-axis combos, not just single deviations).
        System.out.println("\n================ MENTALITY DIAGNOSTIC (points + win/draw/loss per value) ================");
        for (double strength : new double[]{200, 252, 330}) {
            team = new TeamProfile(strength, strength);
            String label = strength < 250 ? "WEAK" : strength < 290 ? "MID" : "STRONG";
            System.out.printf("%n--- %s (att/def %d) vs panel ---%n", label, (int) strength);
            System.out.printf("  %-16s %9s   %5s %5s %5s%n", "mentality", "seasonPts", "win%", "draw%", "loss%");
            for (String m : TacticalModel.MENTALITY_OPTIONS) {
                PersonalizedTactic t = new PersonalizedTactic();
                t.setMentality(m);
                TacticVector v = tss.vector(t);
                double pts = 0, win = 0, draw = 0, loss = 0;
                for (int o = 0; o < oppProf.length; o++) {
                    double[] xg = tss.expectedGoalsForRanking(team, v, oppProf[o], oppTac[o]);
                    double[] wdl = tss.outcomeProbabilities(xg[0], xg[1]);
                    win += wdl[0]; draw += wdl[1]; loss += wdl[2];
                    pts += 2 * (3 * wdl[0] + wdl[1]); // home + away
                }
                int n = oppProf.length;
                System.out.printf("  %-16s %9.2f   %4.0f%% %4.0f%% %4.0f%%%n",
                        m, pts, 100 * win / n, 100 * draw / n, 100 * loss / n);
            }
        }
    }

    private void top10Diversity(String name, boolean enriched) {
        List<PersonalizedTactic> cand = enriched ? enrichedCommitted() : mts.committedAdvisorTactics(team, 60, 0.40);
        record Scored(PersonalizedTactic t, double pts) {}
        List<Scored> scored = new ArrayList<>(cand.size());
        for (PersonalizedTactic t : cand) scored.add(new Scored(t, seasonPoints(t)));
        scored.sort((a, b) -> Double.compare(b.pts(), a.pts()));
        java.util.Set<String> seenPts = new java.util.HashSet<>();
        List<PersonalizedTactic> top = new ArrayList<>();
        for (Scored s : scored) {
            String k = String.format(java.util.Locale.ROOT, "%.2f", s.pts());
            if (seenPts.add(k)) { top.add(s.t()); if (top.size() == 10) break; }
        }
        int constant = 0;
        StringBuilder sb = new StringBuilder();
        for (Axis a : AXES) {
            java.util.Set<String> vals = new java.util.LinkedHashSet<>();
            for (PersonalizedTactic t : top) vals.add(readAxis(t, a));
            if (vals.size() == 1) constant++;
            sb.append(String.format("%s:%d  ", a.name(), vals.size()));
        }
        System.out.printf("%n[%s]  candidates=%d  CONSTANT axes in top-10 = %d  (GOAL < 3-4)%n",
                name, cand.size(), constant);
        System.out.println("   distinct values per axis across top-10: " + sb.toString().trim());
    }

    /** Enriched enumeration: for each of the 900 base settings, greedily commit the new axes, then emit
     *  the reference PLUS a random sample of multi-axis committed variants (each axis independently
     *  flipped to a random non-default value with some probability) — so top tactics can differ in
     *  several axes at once, not just one. */
    private List<PersonalizedTactic> enrichedCommitted() {
        java.util.Random rng = new java.util.Random(42);
        List<PersonalizedTactic> out = new ArrayList<>();
        List<Axis> newAxes = AXES.subList(5, AXES.size()); // the 9 new axes
        for (PersonalizedTactic ref : mts.committedAdvisorTactics(team, 60, 0.40)) {
            out.add(ref); // already includes reference + single deviations
            for (int k = 0; k < 6; k++) {
                PersonalizedTactic v = copyTactic(ref);
                for (Axis a : newAxes) {
                    if (rng.nextDouble() < 0.5) {
                        List<String> opts = a.options();
                        v = setAxis(v, a, opts.get(rng.nextInt(opts.size())));
                    }
                }
                out.add(v);
            }
        }
        return out;
    }

    private PersonalizedTactic setAxis(PersonalizedTactic t, Axis a, String val) { a.setter().accept(t, val); return t; }

    private static PersonalizedTactic copyTactic(PersonalizedTactic s) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(s.getMentality()); t.setTimeWasting(s.getTimeWasting()); t.setInPossession(s.getInPossession());
        t.setPassingType(s.getPassingType()); t.setTempo(s.getTempo()); t.setDefensiveLine(s.getDefensiveLine());
        t.setPressing(s.getPressing()); t.setWidth(s.getWidth()); t.setDribbling(s.getDribbling());
        t.setFoulFrequency(s.getFoulFrequency()); t.setFoulHardness(s.getFoulHardness());
        t.setTempoFragmentation(s.getTempoFragmentation()); t.setWidePlay(s.getWidePlay()); t.setTransition(s.getTransition());
        return t;
    }

    /** Larger categorical→numeric values for the low-impact axes so they have comparable swing. */
    private static void boostMaps(TacticalModel m) {
        m.setDribblingRisk(java.util.Map.of("Less", -1.0, "Standard", 0.0, "More", 1.0));
        m.setFragmentationControl(java.util.Map.of("Flowing", -0.5, "Normal", 0.0, "Fragment", 0.7));
        m.setTransitionRisk(java.util.Map.of("Win Fouls", -0.5, "Balanced", 0.0, "Fast Counter", 0.7));
        m.setTransitionControl(java.util.Map.of("Win Fouls", 0.6, "Balanced", 0.0, "Fast Counter", -0.3));
        m.setWidePlayWidth(java.util.Map.of("Cut Inside", -0.9, "Shoot", 0.0, "Cross", 1.0));
        m.setFoulControl(java.util.Map.of("Rarely", -0.15, "Normal", 0.0, "Often", 0.35));
        m.setFoulHardnessControl(java.util.Map.of("Soft", -0.15, "Medium", 0.0, "Hard", 0.35));
    }

    private void sweepConfig(String name, Runnable apply) {
        cfg = new MatchEngineConfig();           // fresh defaults
        tss.engineConfig = cfg;
        mts.engineConfig = cfg;
        apply.run();
        // rebuild opponents under this config
        setUpOpponentsOnly();
        team = new TeamProfile(200, 200);

        // swing per axis
        record R(String n, double s, String bv) {}
        List<R> rs = new ArrayList<>();
        for (Axis a : AXES) {
            double best = -1e9, worst = 1e9; String bv = "";
            for (String val : a.options()) {
                PersonalizedTactic t = new PersonalizedTactic();
                a.setter().accept(t, val);
                double p = seasonPoints(t);
                if (p > best) { best = p; bv = val; }
                if (p < worst) worst = p;
            }
            rs.add(new R(a.name(), best - worst, bv));
        }
        rs.sort((x, y) -> Double.compare(y.s(), x.s()));
        double max = rs.get(0).s(), median = rs.get(rs.size() / 2).s();
        long meaningful = rs.stream().filter(r -> r.s() >= 0.5).count();
        System.out.printf("%n[%s]  max=%.2f median=%.2f  axes>=0.5: %d/14%n", name, max, median, meaningful);
        StringBuilder sb = new StringBuilder("   ");
        for (R r : rs) sb.append(String.format("%s:%.2f(%s)  ", r.n(), r.s(), r.bv()));
        System.out.println(sb.toString().trim());
    }

    private void runScenario() {
        // Part A: standalone swing per axis (only that axis set, everything else neutral), sorted by
        // swing = the axis PRIORITY.
        record Row(String name, double swing, double best, String worstV, String bestV) {}
        List<Row> rowsA = new ArrayList<>();
        for (Axis a : AXES) {
            double best = -1e9, worst = 1e9; String bestV = "", worstV = "";
            for (String val : a.options()) {
                PersonalizedTactic t = new PersonalizedTactic();
                a.setter().accept(t, val);
                double p = seasonPoints(t);
                if (p > best) { best = p; bestV = val; }
                if (p < worst) { worst = p; worstV = val; }
            }
            rowsA.add(new Row(a.name(), best - worst, best, worstV, bestV));
        }
        rowsA.sort((x, y) -> Double.compare(y.swing(), x.swing()));
        System.out.println("Per-axis swing (PRIORITY, high→low):");
        System.out.printf("  %-14s %8s   %-12s -> %-12s%n", "axis", "swing", "worst-val", "best-val");
        for (Row r : rowsA)
            System.out.printf("  %-14s %8.2f   %-12s -> %-12s%n", r.name(), r.swing(), r.worstV(), r.bestV());

        // Part B: greedy build — add the single best (axis,value) each step, record the marginal gain.
        System.out.println("\nGreedy build (marginal points added per committed axis):");
        PersonalizedTactic chosen = new PersonalizedTactic();
        double current = seasonPoints(chosen);
        System.out.printf("  baseline (all neutral): %.1f pts%n", current);
        List<Axis> remaining = new ArrayList<>(AXES);
        int step = 1;
        while (!remaining.isEmpty()) {
            Axis bestAxis = null; String bestVal = null; double bestPts = current;
            for (Axis a : remaining) {
                String save = readAxis(chosen, a);
                for (String val : a.options()) {
                    a.setter().accept(chosen, val);
                    double p = seasonPoints(chosen);
                    if (p > bestPts) { bestPts = p; bestAxis = a; bestVal = val; }
                }
                a.setter().accept(chosen, save); // restore
            }
            if (bestAxis == null) break;
            bestAxis.setter().accept(chosen, bestVal);
            System.out.printf("  %2d. %-14s = %-12s  marginal +%.3f   (total %.2f)%n",
                    step++, bestAxis.name(), bestVal, bestPts - current, bestPts);
            current = bestPts;
            remaining.remove(bestAxis);
        }
    }

    private static String readAxis(PersonalizedTactic t, Axis a) {
        // crude: re-derive current value by name (only needs to restore, so store via a temp set/get)
        switch (a.name()) {
            case "Mentality": return t.getMentality();
            case "Tempo": return t.getTempo();
            case "Passing": return t.getPassingType();
            case "In Possession": return t.getInPossession();
            case "Time Wasting": return t.getTimeWasting();
            case "Def Line": return t.getDefensiveLine();
            case "Pressing": return t.getPressing();
            case "Width": return t.getWidth();
            case "Dribbling": return t.getDribbling();
            case "Fouls": return t.getFoulFrequency();
            case "Foul Hard": return t.getFoulHardness();
            case "Tempo Frag": return t.getTempoFragmentation();
            case "Wide Play": return t.getWidePlay();
            case "Transition": return t.getTransition();
            default: return null;
        }
    }
}
