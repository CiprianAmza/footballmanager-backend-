package com.footballmanagergamesimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Config-driven FUT-style player card buckets.
 *
 * <p>Each bucket is a sparse attribute-weight profile over the 36 {@code PlayerSkills}
 * attributes exposed via {@code PlayerSkillsService.GETTER_MAP}. Designers can override only
 * the weights they want to tune in {@code application.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "player.card")
public class PlayerCardConfig {

    private Scale attributeScale = new Scale(1.0, 20.0, 0.0, 99.0);
    private Scale overallScale = new Scale(1.0, 300.0, 0.0, 99.0);
    private Map<String, Map<String, Double>> buckets = new HashMap<>();

    public Scale getAttributeScale() {
        return attributeScale;
    }

    public void setAttributeScale(Scale attributeScale) {
        this.attributeScale = attributeScale;
    }

    public Scale getOverallScale() {
        return overallScale;
    }

    public void setOverallScale(Scale overallScale) {
        this.overallScale = overallScale;
    }

    public Map<String, Map<String, Double>> getBuckets() {
        return buckets;
    }

    public void setBuckets(Map<String, Map<String, Double>> buckets) {
        this.buckets = new HashMap<>();
        if (buckets == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Double>> entry : buckets.entrySet()) {
            this.buckets.put(normalizeBucket(entry.getKey()), new LinkedHashMap<>(entry.getValue()));
        }
    }

    public Map<String, Double> bucketWeights(String bucket) {
        String normalizedBucket = normalizeBucket(bucket);
        Map<String, Double> weights = new LinkedHashMap<>();

        Map<String, Double> defaults = DEFAULT_BUCKETS.get(normalizedBucket);
        if (defaults != null) {
            weights.putAll(defaults);
        }

        Map<String, Double> overrides = buckets.get(normalizedBucket);
        if (overrides != null) {
            weights.putAll(overrides);
        }

        return weights;
    }

    public double weight(String bucket, String attribute) {
        return bucketWeights(bucket).getOrDefault(attribute, 0.0);
    }

    private static String normalizeBucket(String bucket) {
        return bucket == null ? "" : bucket.toUpperCase(Locale.ROOT);
    }

    public static class Scale {
        private double sourceMin;
        private double sourceMax;
        private double targetMin;
        private double targetMax;

        public Scale() {
        }

        public Scale(double sourceMin, double sourceMax, double targetMin, double targetMax) {
            this.sourceMin = sourceMin;
            this.sourceMax = sourceMax;
            this.targetMin = targetMin;
            this.targetMax = targetMax;
        }

        public int scaleToInt(double value) {
            if (sourceMax <= sourceMin) {
                return (int) Math.round(targetMin);
            }
            double normalized = (value - sourceMin) / (sourceMax - sourceMin);
            double clamped = Math.max(0.0, Math.min(1.0, normalized));
            double scaled = targetMin + clamped * (targetMax - targetMin);
            return (int) Math.round(scaled);
        }

        public double getSourceMin() {
            return sourceMin;
        }

        public void setSourceMin(double sourceMin) {
            this.sourceMin = sourceMin;
        }

        public double getSourceMax() {
            return sourceMax;
        }

        public void setSourceMax(double sourceMax) {
            this.sourceMax = sourceMax;
        }

        public double getTargetMin() {
            return targetMin;
        }

        public void setTargetMin(double targetMin) {
            this.targetMin = targetMin;
        }

        public double getTargetMax() {
            return targetMax;
        }

        public void setTargetMax(double targetMax) {
            this.targetMax = targetMax;
        }
    }

    private static Map<String, Double> prof(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    private static final Map<String, Map<String, Double>> DEFAULT_BUCKETS = new LinkedHashMap<>();

    static {
        DEFAULT_BUCKETS.put("PAC", prof(
                "Acceleration", 1.0,
                "Pace", 1.0));

        DEFAULT_BUCKETS.put("SHO", prof(
                "Finishing", 1.35,
                "Long Shots", 1.1,
                "Penalty Taking", 0.85,
                "Composure", 0.8,
                "Technique", 0.6,
                "Strength", 0.4));

        DEFAULT_BUCKETS.put("PAS", prof(
                "Passing", 1.2,
                "Crossing", 1.0,
                "Vision", 1.0,
                "Free Kick", 0.85,
                "Corners", 0.7,
                "Technique", 0.6));

        DEFAULT_BUCKETS.put("DRI", prof(
                "Dribbling", 1.2,
                "Agility", 1.0,
                "Balance", 0.95,
                "First Touch", 0.95,
                "Flair", 0.85,
                "Technique", 0.75));

        DEFAULT_BUCKETS.put("DEF", prof(
                "Marking", 1.1,
                "Tackling", 1.2,
                "Positioning", 1.0,
                "Heading", 0.75,
                "Anticipation", 0.85));

        DEFAULT_BUCKETS.put("PHY", prof(
                "Strength", 1.0,
                "Stamina", 1.0,
                "Jumping Reach", 0.9,
                "Aggression", 0.8,
                "Natural Fitness", 0.8));
    }
}
