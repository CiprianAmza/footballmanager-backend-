package com.footballmanagergamesimulator.engine.interaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full {@code parameter × invariant} grid of Sobol indices.
 *
 * <p>This is the multi-axis analog of
 * {@code SensitivityMatrix} from Faza 5. The crucial difference: every cell
 * also reports {@code ST − S1}, so you can see which parameters have purely
 * additive effects vs which only matter in combination with others.
 */
public final class InteractionMatrix {

    private final List<String> parameterNames;
    private final List<String> invariantNames;
    private final Map<String, Map<String, InteractionResult>> cells;
    private final List<PairInteractionResult> pairResults;

    public InteractionMatrix(List<String> parameterNames,
                             List<String> invariantNames,
                             List<InteractionResult> results) {
        this(parameterNames, invariantNames, results, List.of());
    }

    public InteractionMatrix(List<String> parameterNames,
                             List<String> invariantNames,
                             List<InteractionResult> results,
                             List<PairInteractionResult> pairResults) {
        this.parameterNames = List.copyOf(parameterNames);
        this.invariantNames = List.copyOf(invariantNames);
        this.pairResults = List.copyOf(pairResults);
        Map<String, Map<String, InteractionResult>> map = new HashMap<>();
        for (InteractionResult r : results) {
            map.computeIfAbsent(r.parameterName(), k -> new HashMap<>())
                    .put(r.invariantName(), r);
        }
        this.cells = map;
    }

    public List<String> parameterNames() { return parameterNames; }
    public List<String> invariantNames() { return invariantNames; }

    public InteractionResult get(String param, String invariant) {
        Map<String, InteractionResult> row = cells.get(param);
        return row == null ? null : row.get(invariant);
    }

    /** Total ST across all invariants — the foundational-impact ranking. */
    public double totalSt(String parameterName) {
        double sum = 0;
        Map<String, InteractionResult> row = cells.get(parameterName);
        if (row == null) return 0;
        for (InteractionResult r : row.values()) sum += r.stClamped();
        return sum;
    }

    /** Total interaction strength across all invariants. */
    public double totalInteraction(String parameterName) {
        double sum = 0;
        Map<String, InteractionResult> row = cells.get(parameterName);
        if (row == null) return 0;
        for (InteractionResult r : row.values()) sum += r.interactionStrength();
        return sum;
    }

    /** Parameters ranked by total-order impact (ST) descending. */
    public List<ParameterRank> rankByTotalImpact() {
        List<ParameterRank> ranks = new ArrayList<>(parameterNames.size());
        for (String p : parameterNames) ranks.add(new ParameterRank(p, totalSt(p)));
        ranks.sort(Comparator.comparingDouble(ParameterRank::score).reversed());
        return ranks;
    }

    /** Parameters ranked by how much of their effect comes from interactions. */
    public List<ParameterRank> rankByInteraction() {
        List<ParameterRank> ranks = new ArrayList<>(parameterNames.size());
        for (String p : parameterNames) ranks.add(new ParameterRank(p, totalInteraction(p)));
        ranks.sort(Comparator.comparingDouble(ParameterRank::score).reversed());
        return ranks;
    }

    /** Most-interacting parameter for a given invariant. */
    public InteractionResult topInteractor(String invariantName) {
        InteractionResult best = null;
        for (String p : parameterNames) {
            InteractionResult r = get(p, invariantName);
            if (r == null) continue;
            if (best == null || r.interactionStrength() > best.interactionStrength()) {
                best = r;
            }
        }
        return best;
    }

    public record ParameterRank(String parameterName, double score) {}

    // ==================== SECOND-ORDER (PAIR) HELPERS ====================

    public List<PairInteractionResult> pairResults() {
        return pairResults;
    }

    /** Top-K param pairs for a given invariant, ranked by S_ij descending. */
    public List<PairInteractionResult> topPairsForInvariant(String invariantName, int k) {
        return pairResults.stream()
                .filter(r -> r.invariantName().equals(invariantName))
                .sorted(Comparator.comparingDouble(PairInteractionResult::s2).reversed())
                .limit(k)
                .toList();
    }

    /**
     * Top-K pairs globally, summed across invariants. Tells you which
     * combinations of knobs MATTER MOST AS PAIRS across the whole catalog
     * — i.e. which two parameters can't be reasoned about separately.
     */
    public List<PairRank> rankPairsGlobal(int k) {
        Map<String, Double> sums = new HashMap<>();
        Map<String, String[]> nameByKey = new HashMap<>();
        for (PairInteractionResult r : pairResults) {
            String key = r.pairKey();
            sums.merge(key, r.s2(), Double::sum);
            nameByKey.putIfAbsent(key, new String[]{r.paramA(), r.paramB()});
        }
        List<PairRank> ranks = new ArrayList<>(sums.size());
        for (Map.Entry<String, Double> e : sums.entrySet()) {
            String[] pair = nameByKey.get(e.getKey());
            ranks.add(new PairRank(pair[0], pair[1], e.getValue()));
        }
        ranks.sort(Comparator.comparingDouble(PairRank::summedS2).reversed());
        return ranks.size() <= k ? ranks : ranks.subList(0, k);
    }

    public record PairRank(String paramA, String paramB, double summedS2) {
        public String label() { return paramA + " × " + paramB; }
    }
}
