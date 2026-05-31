package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THROWAWAY probe (not for merge): confirms the new {@code panelExpectedPoints} tactic-ranking
 * metric produces a wider, more differentiated landscape than the old self-mirror
 * {@code expectedGoalDifference}, and that tempo/passing now move the ranking. Prints stats to
 * stdout for the report and asserts a couple of sanity invariants.
 */
class TacticLandscapeProbeTest {

    private TacticalScoreService service() {
        TacticalScoreService s = new TacticalScoreService();
        s.engineConfig = new MatchEngineConfig();
        return s;
    }

    private List<PersonalizedTactic> candidates() {
        // Reuse the production 900-tactic enumeration.
        return new ManagerTacticService().candidateTactics();
    }

    @Test
    void newMetricDifferentiatesTheLandscape() {
        TacticalScoreService svc = service();
        // Desert-Lion-like balanced profile (~att 660 / def 630).
        TeamProfile mine = new TeamProfile(660, 630);
        TacticVector neutral = svc.vector(new PersonalizedTactic());

        List<PersonalizedTactic> cand = candidates();

        double[] oldVals = new double[cand.size()]; // expectedGoalDifference vs self-mirror
        double[] newVals = new double[cand.size()]; // panelExpectedPoints
        for (int i = 0; i < cand.size(); i++) {
            TacticVector v = svc.vector(cand.get(i));
            oldVals[i] = svc.expectedGoalDifference(mine, v, mine, neutral);
            newVals[i] = svc.panelExpectedPoints(mine, v);
        }

        Stats oldS = Stats.of(oldVals);
        Stats newS = Stats.of(newVals);

        // Tempo sensitivity: hold everything neutral, vary ONLY tempo, measure spread.
        double[] tempoOld = new double[TEMPOS.size()];
        double[] tempoNew = new double[TEMPOS.size()];
        for (int i = 0; i < TEMPOS.size(); i++) {
            PersonalizedTactic t = new PersonalizedTactic();
            t.setMentality("Balanced"); t.setTimeWasting("Sometimes");
            t.setInPossession("Standard"); t.setPassingType("Normal");
            t.setTempo(TEMPOS.get(i));
            TacticVector v = svc.vector(t);
            tempoOld[i] = svc.expectedGoalDifference(mine, v, mine, neutral);
            tempoNew[i] = svc.panelExpectedPoints(mine, v);
        }
        double tempoRangeOld = Stats.of(tempoOld).range();
        double tempoRangeNew = Stats.of(tempoNew).range();

        System.out.println("=== TACTIC LANDSCAPE PROBE (att 660 / def 630) ===");
        System.out.printf("OLD expectedGoalDifference vs self-mirror: best=%.5f worst=%.5f range=%.5f std=%.5f%n",
                oldS.max, oldS.min, oldS.range(), oldS.std);
        System.out.printf("NEW panelExpectedPoints:                   best=%.5f worst=%.5f range=%.5f std=%.5f%n",
                newS.max, newS.min, newS.range(), newS.std);
        System.out.printf("NEW within 0.01 of best: %d / %d ; within 0.05 of best: %d / %d%n",
                newS.countWithin(0.01), cand.size(), newS.countWithin(0.05), cand.size());
        System.out.printf("OLD within 0.01 of best: %d / %d ; within 0.05 of best: %d / %d%n",
                oldS.countWithin(0.01), cand.size(), oldS.countWithin(0.05), cand.size());
        System.out.printf("Tempo-only spread (5 tempo values): OLD range=%.6f  NEW range=%.6f%n",
                tempoRangeOld, tempoRangeNew);

        // The OLD metric collapses tempo (openness cancels vs a mirror) -> ~0 range.
        assertThat(tempoRangeOld).as("old tempo-only range (mirror cancels openness)").isLessThan(1e-6);
        // The NEW metric makes tempo move the ranking.
        assertThat(tempoRangeNew).as("new tempo-only range").isGreaterThan(1e-3);
        // The NEW metric is a meaningful, differentiated spread.
        assertThat(newS.std).as("new std").isGreaterThan(0.0);
    }

    private static final List<String> TEMPOS =
            List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    private record Stats(double min, double max, double mean, double std, double[] vals) {
        static Stats of(double[] v) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
            for (double x : v) { min = Math.min(min, x); max = Math.max(max, x); sum += x; }
            double mean = sum / v.length;
            double var = 0;
            for (double x : v) var += (x - mean) * (x - mean);
            return new Stats(min, max, mean, Math.sqrt(var / v.length), v);
        }
        double range() { return max - min; }
        int countWithin(double eps) {
            int c = 0;
            for (double x : vals) if (max - x <= eps) c++;
            return c;
        }
    }
}
