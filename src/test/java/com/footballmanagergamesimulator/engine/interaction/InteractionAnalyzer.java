package com.footballmanagergamesimulator.engine.interaction;

import com.footballmanagergamesimulator.engine.invariants.EngineInvariant;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.invariants.InvariantResult;
import com.footballmanagergamesimulator.engine.tuner.ParameterSpace;
import com.footballmanagergamesimulator.engine.tuner.TunableParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Saltelli-scheme orchestrator for Sobol variance decomposition.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Draw 2 independent N-row Latin-Hypercube matrices A and B in
 *       k-dimensional unit hypercube.</li>
 *   <li>Convert each row to a {@link ParameterSpace.Candidate} by mapping
 *       column j onto parameter j's [min, max] range and quantizing.</li>
 *   <li>For each parameter i, build matrix C_i which takes column i from
 *       B and all other columns from A → N more candidates.</li>
 *   <li>For each invariant in the catalog, evaluate every candidate (apply
 *       config + run that single invariant with a fixed per-iteration seed)
 *       → arrays Y_A, Y_B, Y_C_i.</li>
 *   <li>Feed those into {@link SobolEstimator#estimateJansen} to get
 *       (S1, ST) per parameter.</li>
 * </ol>
 *
 * <p>Total model evaluations per analysis: {@code n × (2k + 2)} for each
 * invariant in the catalog — the extra {@code k×n} comes from the BA_i
 * matrices needed for second-order indices. With {@code n=256}, {@code k=5},
 * 10 invariants → 30,720 evaluations × ~400 calculateScores per evaluation
 * ≈ 12M calls ≈ ~2-3 s on modern hardware.
 *
 * <h2>Determinism</h2>
 * Every invariant evaluation uses {@code evalSeed} so different candidates
 * are compared against identical RNG noise. The LHS sampler uses
 * {@code samplingSeed} so the same baseSeed produces the same sample grid.
 */
public final class InteractionAnalyzer {

    private final EngineInvariantSuiteRunner runner;
    private final List<EngineInvariant> catalog;
    private final ParameterSpace space;
    private final long baseSeed;

    /** Number of base rows in each of A and B. Total evaluations = n(k+2) per invariant. */
    public int sampleSize = 128;

    /** Iterations per invariant per candidate. Lower than catalog default (2000)
     *  because Sobol scales as n × iterations. 500 → ±4% precision per estimate. */
    public int iterationsPerEvaluation = 500;

    public InteractionAnalyzer(EngineInvariantSuiteRunner runner,
                               List<EngineInvariant> catalog,
                               ParameterSpace space,
                               long baseSeed) {
        this.runner = runner;
        this.catalog = catalog;
        this.space = space;
        this.baseSeed = baseSeed;
    }

    public InteractionMatrix analyze() {
        int k = space.size();
        int n = sampleSize;
        Random samplingRng = new Random(baseSeed);

        // ---- 1. LHS A and B in [0,1]^k ----
        double[][] aUnit = LatinHypercubeSampler.sample(n, k, samplingRng);
        double[][] bUnit = LatinHypercubeSampler.sample(n, k, samplingRng);

        // ---- 2. Map to parameter ranges + build candidates ----
        ParameterSpace.Candidate[] aCands = mapToCandidates(aUnit);
        ParameterSpace.Candidate[] bCands = mapToCandidates(bUnit);

        // ---- 3. Build C_i (AB_i: col i from B, others from A) ----
        //         and BA_i (col i from A, others from B). Both needed for S2.
        ParameterSpace.Candidate[][] abCands = new ParameterSpace.Candidate[k][n];
        ParameterSpace.Candidate[][] baCands = new ParameterSpace.Candidate[k][n];
        List<TunableParameter> params = space.parameters();
        for (int i = 0; i < k; i++) {
            for (int row = 0; row < n; row++) {
                java.util.Map<String, Double> abValues = new java.util.LinkedHashMap<>();
                java.util.Map<String, Double> baValues = new java.util.LinkedHashMap<>();
                for (int col = 0; col < k; col++) {
                    String name = params.get(col).name();
                    abValues.put(name, (col == i) ? bCands[row].values().get(name)
                                                  : aCands[row].values().get(name));
                    baValues.put(name, (col == i) ? aCands[row].values().get(name)
                                                  : bCands[row].values().get(name));
                }
                abCands[i][row] = new ParameterSpace.Candidate(abValues);
                baCands[i][row] = new ParameterSpace.Candidate(baValues);
            }
        }

        // ---- 4. For each invariant, evaluate all candidates and compute indices ----
        List<String> paramNames = new ArrayList<>(k);
        for (TunableParameter p : params) paramNames.add(p.name());
        List<String> invNames = new ArrayList<>(catalog.size());
        for (EngineInvariant inv : catalog) invNames.add(inv.name());

        List<InteractionResult> results = new ArrayList<>(k * catalog.size());
        List<PairInteractionResult> pairResults = new ArrayList<>(catalog.size() * k * (k - 1) / 2);

        for (EngineInvariant invariant : catalog) {
            double[] yA = new double[n];
            double[] yB = new double[n];
            double[][] yAB = new double[k][n];
            double[][] yBA = new double[k][n];

            for (int row = 0; row < n; row++) {
                yA[row] = evaluate(invariant, aCands[row]);
                yB[row] = evaluate(invariant, bCands[row]);
            }
            for (int i = 0; i < k; i++) {
                for (int row = 0; row < n; row++) {
                    yAB[i][row] = evaluate(invariant, abCands[i][row]);
                    yBA[i][row] = evaluate(invariant, baCands[i][row]);
                }
            }

            SobolEstimator.Indices[] indices = SobolEstimator.estimateJansen(yA, yB, yAB);
            for (int i = 0; i < k; i++) {
                results.add(new InteractionResult(
                        paramNames.get(i), invariant.name(),
                        indices[i].s1(), indices[i].st()));
            }

            // S2 — pure pair interactions
            double[] s2 = SobolEstimator.estimateSecondOrderSaltelli(yA, yB, yAB, yBA, indices);
            int idx = 0;
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    pairResults.add(new PairInteractionResult(
                            invariant.name(),
                            paramNames.get(i), paramNames.get(j),
                            s2[idx++]));
                }
            }
        }

        return new InteractionMatrix(paramNames, invNames, results, pairResults);
    }

    /** Apply candidate to bound config, run one invariant, return win-rate-A. */
    private double evaluate(EngineInvariant invariant, ParameterSpace.Candidate candidate) {
        space.apply(candidate);
        // Use a single low-iteration variant of the invariant so Sobol scaling stays sane.
        EngineInvariant downsized = new EngineInvariant(
                invariant.name(), invariant.description(),
                invariant.setupA(), invariant.setupB(),
                iterationsPerEvaluation,
                invariant.minWinRateA(), invariant.maxWinRateA());
        // Fixed seed per evaluation → noise cancels out across candidates.
        InvariantResult r = runner.runOne(downsized, baseSeed + 0xDEADBEEFL);
        return r.winRateA();
    }

    /** Convert N×k unit-hypercube matrix into N candidates on the parameter grid. */
    private ParameterSpace.Candidate[] mapToCandidates(double[][] unit) {
        int n = unit.length;
        int k = space.size();
        List<TunableParameter> params = space.parameters();
        ParameterSpace.Candidate[] out = new ParameterSpace.Candidate[n];
        for (int row = 0; row < n; row++) {
            java.util.Map<String, Double> values = new java.util.LinkedHashMap<>();
            for (int col = 0; col < k; col++) {
                TunableParameter p = params.get(col);
                double raw = p.min() + unit[row][col] * (p.max() - p.min());
                values.put(p.name(), p.quantize(raw));
            }
            out[row] = new ParameterSpace.Candidate(values);
        }
        return out;
    }
}
