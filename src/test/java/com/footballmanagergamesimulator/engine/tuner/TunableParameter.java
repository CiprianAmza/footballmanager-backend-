package com.footballmanagergamesimulator.engine.tuner;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * One numeric knob the auto-tuner can sweep.
 *
 * <p>The {@code getter}/{@code setter} pair is bound at construction time to
 * whatever object owns the value — typically a {@code MatchEngineConfig}
 * sub-section or the {@code EngineInvariantSuiteRunner} itself. This keeps
 * the tuner agnostic about where parameters live.
 *
 * <p>{@code step} is the discretization grid: random search samples uniformly
 * on {@code [min, max]} but quantizes to multiples of {@code step}; hill
 * climbing perturbs by ±{@code step}. A coarse step (e.g. 0.1 over a 1.5
 * range) converges faster but may miss fine sweet spots.
 *
 * @param name    human-readable parameter id, e.g. "power.ratioExponent"
 * @param min     inclusive lower bound
 * @param max     inclusive upper bound
 * @param step    discretization grid + hill-climb perturbation size
 * @param getter  read current value (must reflect post-set value)
 * @param setter  write new value (must take effect immediately)
 */
public record TunableParameter(
        String name,
        double min,
        double max,
        double step,
        DoubleSupplier getter,
        DoubleConsumer setter) {

    public double get() {
        return getter.getAsDouble();
    }

    public void set(double value) {
        setter.accept(clamp(value));
    }

    /** Snap a raw value to the {@code step} grid then clamp into {@code [min, max]}. */
    public double quantize(double value) {
        double snapped = min + Math.round((value - min) / step) * step;
        return clamp(snapped);
    }

    public double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }

    /** Total number of grid points in this parameter's range (inclusive). */
    public int gridSize() {
        return (int) Math.round((max - min) / step) + 1;
    }
}
