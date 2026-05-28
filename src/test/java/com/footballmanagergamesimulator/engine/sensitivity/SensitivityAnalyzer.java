package com.footballmanagergamesimulator.engine.sensitivity;

import com.footballmanagergamesimulator.engine.invariants.EngineInvariant;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.invariants.InvariantResult;
import com.footballmanagergamesimulator.engine.tuner.ParameterSpace;
import com.footballmanagergamesimulator.engine.tuner.TunableParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-axis-at-a-time sensitivity scan over {@link ParameterSpace}.
 *
 * <h2>What it measures</h2>
 * For every {@link TunableParameter} P, the analyzer:
 * <ol>
 *   <li>Snapshots P's current value V0</li>
 *   <li>Sets P = min, runs the catalog → records win-rate-A per invariant</li>
 *   <li>Sets P = max, runs the catalog → records win-rate-A per invariant</li>
 *   <li>Computes ppShift = rateAtMax − rateAtMin per invariant</li>
 *   <li>Restores P = V0 so the next param starts from the same baseline</li>
 * </ol>
 *
 * <p>This is a <b>coarse</b> sensitivity (full-range swing, not gradient at
 * a point). The trade-off: cheap (2 evaluations per parameter) and answers
 * "if I crank this knob fully, how much does the engine react?".
 *
 * <p>For derivative-at-a-point you'd run with small perturbations (e.g.
 * ±1 step around the baseline) — see {@link #setSwingMode}.
 *
 * <h2>Determinism</h2>
 * Every catalog run uses the same {@code baseSeed} → results are
 * reproducible, comparable across params.
 */
public final class SensitivityAnalyzer {

    private final EngineInvariantSuiteRunner runner;
    private final List<EngineInvariant> catalog;
    private final ParameterSpace space;

    /**
     * If {@code true} (default), sweeps the full {@code [min, max]} range —
     * answers "how reactive is the engine to this knob in its allowed range".
     * If {@code false}, perturbs by ±{@code step} around the current baseline
     * — answers "what's the local derivative around the current operating
     * point". The two views can disagree dramatically for non-linear effects.
     */
    private boolean fullRangeMode = true;

    public SensitivityAnalyzer(EngineInvariantSuiteRunner runner,
                               List<EngineInvariant> catalog,
                               ParameterSpace space) {
        this.runner = runner;
        this.catalog = catalog;
        this.space = space;
    }

    /** Switch between full-range swing (default) and local ±1-step derivative. */
    public void setSwingMode(boolean fullRange) {
        this.fullRangeMode = fullRange;
    }

    public SensitivityMatrix analyze() {
        // ---- Baseline run: per-invariant win rate at current config ----
        Map<String, Double> baselineRates = new LinkedHashMap<>();
        List<InvariantResult> baseline = runner.runAll(catalog);
        for (InvariantResult r : baseline) {
            baselineRates.put(r.invariant().name(), r.winRateA());
        }

        List<String> paramNames = new ArrayList<>(space.size());
        List<String> invariantNames = new ArrayList<>(catalog.size());
        for (EngineInvariant inv : catalog) invariantNames.add(inv.name());
        List<SensitivityResult> cells = new ArrayList<>(space.size() * catalog.size());

        // ---- For each parameter: snapshot, low-run, high-run, restore ----
        for (TunableParameter p : space.parameters()) {
            paramNames.add(p.name());
            double original = p.get();

            double low, high;
            if (fullRangeMode) {
                low = p.min();
                high = p.max();
            } else {
                low = p.clamp(original - p.step());
                high = p.clamp(original + p.step());
            }

            // Low end
            p.set(low);
            Map<String, Double> ratesAtLow = runCatalogRates();

            // High end
            p.set(high);
            Map<String, Double> ratesAtHigh = runCatalogRates();

            // Restore baseline so the next param starts clean
            p.set(original);

            for (EngineInvariant inv : catalog) {
                double rl = ratesAtLow.getOrDefault(inv.name(), 0.0);
                double rh = ratesAtHigh.getOrDefault(inv.name(), 0.0);
                cells.add(new SensitivityResult(p.name(), inv.name(), rl, rh, rh - rl));
            }
        }

        return new SensitivityMatrix(paramNames, invariantNames, cells, baselineRates);
    }

    private Map<String, Double> runCatalogRates() {
        Map<String, Double> rates = new HashMap<>();
        List<InvariantResult> results = runner.runAll(catalog);
        for (InvariantResult r : results) {
            rates.put(r.invariant().name(), r.winRateA());
        }
        return rates;
    }
}
