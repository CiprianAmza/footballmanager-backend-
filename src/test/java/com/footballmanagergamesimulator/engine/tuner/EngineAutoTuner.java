package com.footballmanagergamesimulator.engine.tuner;

import com.footballmanagergamesimulator.engine.invariants.EngineInvariant;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.invariants.InvariantResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Two-phase parameter-space search that finds the {@link ParameterSpace.Candidate}
 * which best satisfies a catalog of {@link EngineInvariant}s.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Phase 1 — Random search:</b> Sample {@code randomIterations} uniformly
 *       random points on the parameter grid, evaluate each, keep the best by
 *       fitness.</li>
 *   <li><b>Phase 2 — Hill climbing:</b> From the best random-search candidate,
 *       try every ±1-step neighbor; if any improves fitness, jump there and
 *       repeat. Terminates when no neighbor wins, or after
 *       {@code maxHillClimbSteps}.</li>
 * </ol>
 *
 * <h2>Fitness</h2>
 * Higher = better. The score is {@code -Σ quadratic_violation(invariant)}
 * where {@code quadratic_violation} is the squared distance from the target
 * win-rate range (0 if inside). Quadratic so the tuner aggressively flattens
 * large violations before fine-tuning small ones.
 *
 * <h2>Determinism</h2>
 * The tuner uses {@code baseSeed} for both its own random-search RNG AND for
 * each invariant evaluation (via the runner's seed). Two runs with the same
 * seed + catalog + space produce identical {@link TuningResult}s.
 */
public final class EngineAutoTuner {

    private final EngineInvariantSuiteRunner runner;
    private final List<EngineInvariant> catalog;
    private final ParameterSpace space;
    private final long baseSeed;

    public int randomIterations = 200;
    public int maxHillClimbSteps = 200;

    public EngineAutoTuner(EngineInvariantSuiteRunner runner,
                           List<EngineInvariant> catalog,
                           ParameterSpace space,
                           long baseSeed) {
        this.runner = runner;
        this.catalog = catalog;
        this.space = space;
        this.baseSeed = baseSeed;
    }

    public TuningResult tune() {
        List<TuningResult.TrajectoryPoint> trajectory = new ArrayList<>();
        Random rng = new Random(baseSeed);

        // ---------- Phase 1: random search ----------
        ParameterSpace.Candidate best = null;
        double bestFitness = Double.NEGATIVE_INFINITY;
        List<InvariantResult> bestResults = null;

        for (int i = 0; i < randomIterations; i++) {
            ParameterSpace.Candidate candidate = space.sampleRandom(rng);
            Evaluation eval = evaluate(candidate);
            if (eval.fitness > bestFitness) {
                best = candidate;
                bestFitness = eval.fitness;
                bestResults = eval.results;
                trajectory.add(new TuningResult.TrajectoryPoint("random-search", i + 1, bestFitness));
            }
        }

        // ---------- Phase 2: hill climbing ----------
        int hillSteps = 0;
        if (best != null) {
            boolean improved = true;
            while (improved && hillSteps < maxHillClimbSteps) {
                improved = false;
                List<ParameterSpace.Candidate> neighbors = space.neighbors(best);
                for (ParameterSpace.Candidate neighbor : neighbors) {
                    hillSteps++;
                    Evaluation eval = evaluate(neighbor);
                    if (eval.fitness > bestFitness + 1e-9) {
                        best = neighbor;
                        bestFitness = eval.fitness;
                        bestResults = eval.results;
                        improved = true;
                        trajectory.add(new TuningResult.TrajectoryPoint("hill-climb", hillSteps, bestFitness));
                        break; // greedy: jump to first improving neighbor
                    }
                    if (hillSteps >= maxHillClimbSteps) break;
                }
            }
        }

        // Restore current bound state to the best so the caller sees winning config.
        if (best != null) space.apply(best);

        return new TuningResult(best, bestFitness, bestResults, trajectory,
                randomIterations + hillSteps, randomIterations, hillSteps);
    }

    /** Apply a candidate, run the catalog, score it. */
    private Evaluation evaluate(ParameterSpace.Candidate candidate) {
        space.apply(candidate);
        // Each invariant gets a fixed per-run seed so the same candidate
        // always scores identically (no RNG drift between evaluate() calls).
        List<InvariantResult> results = runner.runAll(catalog);
        double fitness = fitness(results);
        return new Evaluation(results, fitness);
    }

    /** Quadratic-violation fitness; 0 is perfect, more negative = worse. */
    static double fitness(List<InvariantResult> results) {
        double penalty = 0;
        for (InvariantResult r : results) {
            double rate = r.winRateA();
            double min = r.invariant().minWinRateA();
            double max = r.invariant().maxWinRateA();
            double dist = 0;
            if (rate < min) dist = min - rate;
            else if (rate > max) dist = rate - max;
            penalty += dist * dist;
        }
        return -penalty;
    }

    private record Evaluation(List<InvariantResult> results, double fitness) {}
}
