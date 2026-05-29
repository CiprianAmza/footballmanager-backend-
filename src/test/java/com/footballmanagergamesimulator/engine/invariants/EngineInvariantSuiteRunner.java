package com.footballmanagergamesimulator.engine.invariants;

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
 * input by calling the SAME production method the game uses
 * ({@link MatchSimulationService#effectivePower}), which reads the morale +
 * home-advantage knobs straight from {@code MatchEngineConfig.power}. So the
 * invariant catalog, the tuner, and the live game all share one set of values —
 * tweak {@code MatchEngineConfig} and every layer moves together.
 */
public class EngineInvariantSuiteRunner {

    private final MatchSimulationService simService;
    private final long baseSeed;

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

    /** Apply morale + home multipliers to base squad power, via the production
     *  method so the catalog and the game use identical config values. */
    public double effectivePower(MatchSetup setup) {
        return simService.effectivePower(setup.basePower(), setup.morale(), setup.home());
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
}
