package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the synthetic Faza 1 analytics. No repositories needed —
 * {@code synthesize}, {@code percentile} and {@code buildHeatmap} are deterministic
 * functions of attributes + a plain {@link MatchEngineConfig} (shipped defaults).
 */
class PlayerAnalyticsServiceTest {

    private PlayerAnalyticsService service;
    private MatchEngineConfig.Analytics cfg;

    @BeforeEach
    void setUp() {
        service = new PlayerAnalyticsService();
        MatchEngineConfig engineConfig = new MatchEngineConfig();
        service.engineConfig = engineConfig;
        cfg = engineConfig.getAnalytics();
    }

    /** All 36 attributes set to one value — gives a clean "all-low" vs "all-high" player. */
    private PlayerSkills uniformSkills(int value, String position) {
        PlayerSkills s = new PlayerSkills();
        s.setPosition(position);
        for (String attr : PlayerSkillsService.GETTER_MAP.keySet()) {
            PlayerSkillsService.SETTER_MAP.get(attr).accept(s, value);
        }
        return s;
    }

    @Test
    void synthesize_isDeterministic() {
        PlayerSkills s = uniformSkills(14, "MC");
        double first = service.synthesize("Defensive Actions", s, cfg, "Standard");
        double second = service.synthesize("Defensive Actions", s, cfg, "Standard");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void higherRelevantAttributes_yieldHigherMetric() {
        PlayerSkills weak = uniformSkills(5, "MC");
        PlayerSkills strong = uniformSkills(18, "MC");
        for (String metric : cfg.metricNames()) {
            double low = service.synthesize(metric, weak, cfg, "Standard");
            double high = service.synthesize(metric, strong, cfg, "Standard");
            assertThat(high)
                    .as("metric %s should increase with attributes", metric)
                    .isGreaterThanOrEqualTo(low);
        }
        // At least the unbounded metrics strictly increase (Pass % is clamped, so >=).
        assertThat(service.synthesize("Pressures", strong, cfg, "Standard"))
                .isGreaterThan(service.synthesize("Pressures", weak, cfg, "Standard"));
    }

    @Test
    void higherPressTactic_increasesPressures() {
        PlayerSkills s = uniformSkills(14, "MC");
        double low = service.synthesize("Pressures", s, cfg, "Low");
        double high = service.synthesize("Pressures", s, cfg, "High");
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void percentile_isAlwaysInRange() {
        List<Double> pool = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        assertThat(service.percentile(pool, -100)).isBetween(0.0, 100.0);
        assertThat(service.percentile(pool, 100)).isBetween(0.0, 100.0);
        assertThat(service.percentile(pool, 3.0)).isBetween(0.0, 100.0);
        // empty pool defaults to median
        assertThat(service.percentile(List.of(), 7.0)).isEqualTo(50.0);
    }

    @Test
    void percentile_isMonotoneInValue() {
        List<Double> pool = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        double low = service.percentile(pool, 1.5);
        double mid = service.percentile(pool, 3.0);
        double high = service.percentile(pool, 4.5);
        assertThat(low).isLessThan(mid);
        assertThat(mid).isLessThan(high);
        assertThat(high).isLessThanOrEqualTo(100.0);
    }

    @Test
    void heatmap_isNormalizedAndDeterministic() {
        PlayerSkills s = uniformSkills(14, "ST");
        double[][] first = service.buildHeatmap(cfg, "ATT", s);
        double[][] second = service.buildHeatmap(cfg, "ATT", s);

        double max = 0.0;
        for (double[] row : first)
            for (double v : row) {
                assertThat(v).isBetween(0.0, 1.0);
                if (v > max) max = v;
            }
        assertThat(max).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.001));

        // deterministic
        for (int r = 0; r < first.length; r++)
            assertThat(second[r]).containsExactly(first[r]);
    }
}
