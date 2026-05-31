package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Map;
import java.util.function.Function;

/**
 * Single source of truth for the attribute → metric synthesis used by BOTH
 * {@link PlayerAnalyticsService} (Faza 1, projected) and {@link PlayerMatchStatService}
 * (Faza 2, accumulated-per-match). Keeping the formula here guarantees the two phases
 * stay consistent: a Faza-2 accumulated per-90 value for a player who appeared in N
 * matches under a "Standard" pressing tactic converges to the same number Faza 1 would
 * have projected.
 *
 * <p>The synthesized per-90 value of a metric is
 * {@code base * (weightedAttrAvg/20) ^ exponent}, where {@code weightedAttrAvg} is the
 * weight-normalized average (1..20) of the metric's referenced attributes; a pressing
 * multiplier is then applied to the pressure family. Monotone increasing in every
 * referenced attribute. Purely arithmetic — no randomness, no I/O.
 */
public final class AnalyticsFormula {

    private AnalyticsFormula() {}

    /**
     * Synthetic per-90 for {@code metric} from {@code skills} under the given pressing
     * key. Identical math to the original {@code PlayerAnalyticsService.synthesize}.
     */
    public static double synthesize(String metric, PlayerSkills skills,
                                    MatchEngineConfig.Analytics cfg, String pressKey) {
        Map<String, Double> weights = cfg.metricWeights(metric);
        if (weights.isEmpty()) return 0.0;

        double weightSum = 0.0;
        double acc = 0.0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            int attr = attributeValue(skills, e.getKey());
            acc += attr * e.getValue();
            weightSum += e.getValue();
        }
        double weightedAvg = weightSum > 0 ? acc / weightSum : 0.0; // 1..20 scale
        double normalized = Math.max(0.0, Math.min(1.0, weightedAvg / 20.0));

        double base = cfg.metricBase(metric);
        double exp = cfg.metricExponent(metric);
        double value = base * Math.pow(normalized, exp);

        if (isPressureMetric(metric)) {
            value *= cfg.pressMetricMultiplier(pressKey);
        }
        if ("Pass %".equals(metric)) {
            value = Math.max(40.0, Math.min(99.0, value));
        }
        return value;
    }

    public static boolean isPressureMetric(String metric) {
        return metric.startsWith("Pressure") || metric.startsWith("Counterpressure") || "Pressures".equals(metric);
    }

    /** Reads a named attribute (1-20) via the shared GETTER_MAP; 1 when skills/attr missing. */
    public static int attributeValue(PlayerSkills skills, String attrName) {
        if (skills == null) return 1;
        Function<PlayerSkills, Integer> getter = PlayerSkillsService.GETTER_MAP.get(attrName);
        if (getter == null) return 1;
        Integer v = getter.apply(skills);
        return v == null ? 1 : v;
    }
}
