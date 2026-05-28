package com.footballmanagergamesimulator.engine.invariants;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.MatchSimulationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Runs a catalog of {@link EngineInvariant}s against the live engine.
 *
 * <p>Each invariant runs {@code iterations} matches with seeded RNG (so two
 * runs of the suite over the same config produce identical results — the
 * determinism guarantee added in Faza 2).
 *
 * <p>This class is the bridge between the engine and the auto-tuner (Faza 4):
 * the tuner instantiates the runner, applies a candidate {@link MatchEngineConfig},
 * runs the suite, scores fitness from the {@link InvariantResult}s, and
 * iterates. Production code never invokes this runner.
 *
 * <h2>Effective power model</h2>
 * The runner converts a {@link MatchSetup} into the {@code calculateScores}
 * input via a deliberately simple curve so the catalog can target individual
 * factors:
 * <ul>
 *   <li><b>Morale</b>: linear from 0.6× at morale=0 to 1.2× at morale=100
 *       (so a side with morale=100 is ~20% "stronger" on paper than one with
 *       morale=50, while morale=20 is ~10% weaker). Knob: {@link #moraleFloor},
 *       {@link #moraleSpread}.</li>
 *   <li><b>Home advantage</b>: ×{@link #homeAdvantage} multiplier (default
 *       1.10 = ~+10% effective power). Off when {@code setup.home() == false}.</li>
 * </ul>
 *
 * <p>These knobs are NOT yet in {@code MatchEngineConfig} — they live on the
 * runner so the catalog can vary them independently of production config.
 * Faza 4's auto-tuner can promote them to config if/when needed.
 */
public class EngineInvariantSuiteRunner {

    private final MatchSimulationService simService;
    private final long baseSeed;

    /** Morale=0 → effectivePower = base × moraleFloor. */
    public double moraleFloor = 0.6;
    /** Morale=100 → effectivePower = base × (moraleFloor + moraleSpread). */
    public double moraleSpread = 0.6;
    /** Home side gets this multiplier. */
    public double homeAdvantage = 1.10;

    public EngineInvariantSuiteRunner(MatchSimulationService simService, long baseSeed) {
        this.simService = simService;
        this.baseSeed = baseSeed;
    }

    /**
     * Run the entire suite. Each invariant uses its own seeded {@link Random}
     * (derived from {@code baseSeed + index}) so identical suite runs yield
     * identical {@link InvariantResult}s.
     */
    public List<InvariantResult> runAll(List<EngineInvariant> invariants) {
        List<InvariantResult> results = new ArrayList<>(invariants.size());
        for (int i = 0; i < invariants.size(); i++) {
            results.add(runOne(invariants.get(i), baseSeed + i * 1000L));
        }
        return results;
    }

    /**
     * Run a single invariant with a specific seed. Public so the auto-tuner
     * (Faza 4) can run subset evaluations during local search without going
     * through the whole catalog.
     */
    public InvariantResult runOne(EngineInvariant invariant, long seed) {
        double powerA = effectivePower(invariant.setupA());
        double powerB = effectivePower(invariant.setupB());

        Random rng = new Random(seed);
        simService.setRandomForTesting(rng);
        try {
            int winsA = 0, draws = 0, winsB = 0;
            long goalsA = 0, goalsB = 0;
            for (int i = 0; i < invariant.iterations(); i++) {
                List<Integer> scores = simService.calculateScores(powerA, powerB);
                int a = scores.get(0);
                int b = scores.get(1);
                goalsA += a;
                goalsB += b;
                if (a > b) winsA++;
                else if (a == b) draws++;
                else winsB++;
            }
            double rateA = winsA / (double) invariant.iterations();
            boolean passed = rateA >= invariant.minWinRateA() - 1e-9
                          && rateA <= invariant.maxWinRateA() + 1e-9;
            double avgA = goalsA / (double) invariant.iterations();
            double avgB = goalsB / (double) invariant.iterations();
            return new InvariantResult(invariant, winsA, draws, winsB, avgA, avgB, passed);
        } finally {
            // Always restore production RNG so leaking seeded state across tests doesn't happen.
            simService.setRandomForTesting(new Random());
        }
    }

    /** Apply morale + home multipliers to base squad power. */
    public double effectivePower(MatchSetup setup) {
        double moraleMult = moraleFloor + moraleSpread * (setup.morale() / 100.0);
        double homeMult = setup.home() ? homeAdvantage : 1.0;
        return setup.basePower() * moraleMult * homeMult;
    }

    /**
     * Pretty-print a tabular report of a {@link #runAll} result. Used by IT
     * test output + future tuner UI.
     */
    public static String report(List<InvariantResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== EngineInvariantSuite report ===\n");
        int passed = 0;
        for (InvariantResult r : results) {
            sb.append("  ").append(r.diagnostic()).append('\n');
            if (r.passed()) passed++;
        }
        sb.append(String.format("=== Totals: %d / %d passed (%.0f%%) ===%n",
                passed, results.size(), passed * 100.0 / Math.max(1, results.size())));
        return sb.toString();
    }

    /** Apply a config tweak to override engine defaults before a run. */
    public void overrideConfigField(MatchEngineConfig.Power power, double ratioExponent) {
        power.setRatioExponent(ratioExponent);
    }
}
