package com.footballmanagergamesimulator.engine.tuner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The set of {@link TunableParameter}s the auto-tuner is allowed to vary,
 * with helpers for random sampling + neighbor iteration.
 *
 * <p>A "candidate" is just a snapshot of all parameter values; the tuner
 * applies it by calling {@link #apply(Candidate)} which writes through to
 * the bound config/runner via each parameter's setter.
 *
 * <p>The space is intentionally simple — no constraints between parameters,
 * no log-scale axes. If you need fancier sweeping (Bayesian, GP, etc.) build
 * it as a new {@code EngineAutoTuner} subclass over the same space.
 */
public final class ParameterSpace {

    private final List<TunableParameter> parameters;

    public ParameterSpace(List<TunableParameter> parameters) {
        this.parameters = List.copyOf(parameters);
    }

    public List<TunableParameter> parameters() {
        return parameters;
    }

    public int size() {
        return parameters.size();
    }

    /** Total grid size (product of per-param grid sizes). Used for diagnostics. */
    public long totalGridSize() {
        long product = 1;
        for (TunableParameter p : parameters) {
            product *= p.gridSize();
        }
        return product;
    }

    /** Snapshot the current bound values into a candidate (i.e. "what's in play right now"). */
    public Candidate captureCurrent() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (TunableParameter p : parameters) {
            values.put(p.name(), p.get());
        }
        return new Candidate(values);
    }

    /** Pick a uniformly random point on the grid. */
    public Candidate sampleRandom(Random rng) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (TunableParameter p : parameters) {
            double raw = p.min() + rng.nextDouble() * (p.max() - p.min());
            values.put(p.name(), p.quantize(raw));
        }
        return new Candidate(values);
    }

    /** Write a candidate back through the bound setters. */
    public void apply(Candidate candidate) {
        for (TunableParameter p : parameters) {
            Double v = candidate.values().get(p.name());
            if (v != null) p.set(v);
        }
    }

    /**
     * Enumerate the 2N grid neighbors of a candidate (±1 step on each axis,
     * staying inside [min, max]). Used by the hill-climber.
     */
    public List<Candidate> neighbors(Candidate origin) {
        List<Candidate> out = new ArrayList<>(parameters.size() * 2);
        for (TunableParameter p : parameters) {
            double current = origin.values().getOrDefault(p.name(), p.get());
            for (int dir : new int[]{-1, 1}) {
                double moved = p.quantize(current + dir * p.step());
                if (Math.abs(moved - current) < p.step() * 0.5) continue;  // off-grid edge
                Map<String, Double> copy = new LinkedHashMap<>(origin.values());
                copy.put(p.name(), moved);
                out.add(new Candidate(copy));
            }
        }
        return out;
    }

    /** Immutable parameter-name → value mapping representing one config point. */
    public record Candidate(Map<String, Double> values) {
        public Candidate {
            values = Map.copyOf(values);
        }

        /** Pretty-print like {@code "k1=v1, k2=v2"}. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Double> e : values.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append('=').append(String.format("%.4f", e.getValue()));
                first = false;
            }
            return sb.toString();
        }
    }
}
