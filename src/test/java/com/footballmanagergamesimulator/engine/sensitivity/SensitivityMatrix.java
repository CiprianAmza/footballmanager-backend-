package com.footballmanagergamesimulator.engine.sensitivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one sensitivity scan: a {@code parameter × invariant} grid
 * of {@link SensitivityResult}s plus convenience methods for ranking.
 *
 * <p>Use this to answer questions like:
 * <ul>
 *   <li><b>Which knob most affects this invariant?</b> →
 *       {@link #topDriver(String)}</li>
 *   <li><b>Which knob has the biggest overall impact?</b> →
 *       {@link #rankParametersByImpact()}</li>
 *   <li><b>Which knobs can I drop from tuning?</b> → bottom of
 *       {@link #rankParametersByImpact()} (impact ≈ 0)</li>
 *   <li><b>How much pp does morale add to championship?</b> →
 *       {@link #get(String, String)}</li>
 * </ul>
 */
public final class SensitivityMatrix {

    private final List<String> parameterNames;
    private final List<String> invariantNames;
    private final Map<String, Map<String, SensitivityResult>> cells; // param → invariant → result
    private final Map<String, Double> baselineRates; // invariant → baseline winRateA

    public SensitivityMatrix(List<String> parameterNames,
                             List<String> invariantNames,
                             List<SensitivityResult> results,
                             Map<String, Double> baselineRates) {
        this.parameterNames = List.copyOf(parameterNames);
        this.invariantNames = List.copyOf(invariantNames);
        this.baselineRates = Map.copyOf(baselineRates);
        Map<String, Map<String, SensitivityResult>> map = new HashMap<>();
        for (SensitivityResult r : results) {
            map.computeIfAbsent(r.parameterName(), k -> new HashMap<>())
                    .put(r.invariantName(), r);
        }
        this.cells = map;
    }

    public List<String> parameterNames() { return parameterNames; }
    public List<String> invariantNames() { return invariantNames; }
    public Map<String, Double> baselineRates() { return baselineRates; }

    /** Lookup a single cell. */
    public SensitivityResult get(String param, String invariant) {
        Map<String, SensitivityResult> row = cells.get(param);
        return row == null ? null : row.get(invariant);
    }

    /**
     * Total absolute pp-shift this parameter causes across all invariants.
     * High score = "this knob matters a lot somewhere"; low score = "inert,
     * could be removed from tuning."
     */
    public double impactScore(String parameterName) {
        double sum = 0;
        Map<String, SensitivityResult> row = cells.get(parameterName);
        if (row == null) return 0;
        for (SensitivityResult r : row.values()) {
            sum += r.magnitude();
        }
        return sum;
    }

    /** Parameters ranked by {@link #impactScore} descending. */
    public List<ParameterRank> rankParametersByImpact() {
        List<ParameterRank> ranks = new ArrayList<>(parameterNames.size());
        for (String p : parameterNames) {
            ranks.add(new ParameterRank(p, impactScore(p)));
        }
        ranks.sort(Comparator.comparingDouble(ParameterRank::impactScore).reversed());
        return ranks;
    }

    /**
     * The parameter that most affects a given invariant's win rate. Useful
     * when one specific invariant fails — tells you which knob to turn.
     */
    public SensitivityResult topDriver(String invariantName) {
        SensitivityResult best = null;
        for (String p : parameterNames) {
            SensitivityResult r = get(p, invariantName);
            if (r == null) continue;
            if (best == null || r.magnitude() > best.magnitude()) {
                best = r;
            }
        }
        return best;
    }

    /** One row of the "parameter impact" leaderboard. */
    public record ParameterRank(String parameterName, double impactScore) {}
}
