package com.footballmanagergamesimulator.compartment.calibration;

/** Produces a legal numeric neighbour for a catalog leaf. */
public final class CanonicalWeightPerturbation {
    private CanonicalWeightPerturbation() {}

    public static double validAlternative(CanonicalScoringWeightKey leaf) {
        if (leaf == null || leaf.type() == CanonicalScoringWeightKey.Type.DISCRETE) {
            throw new IllegalArgumentException("numeric leaf required");
        }
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        String path = leaf.path();
        double value;
        if (leaf.type() == CanonicalScoringWeightKey.Type.INTEGER) {
            value = baseline >= 20 ? baseline - 1 : baseline + 1;
        } else if (path.endsWith("attribute-min")) {
            value = baseline >= 20 ? 19 : Math.max(1, baseline + 1);
        } else if (path.endsWith("attribute-max")) {
            value = baseline >= 20 ? 19 : Math.min(20, baseline + 1);
        } else if (path.contains("quantile")) {
            value = baseline >= 0.9 ? Math.max(0.01, baseline - 0.05) : Math.min(0.99, baseline + 0.05);
        } else if (path.endsWith("clamp-min")) {
            value = baseline + 0.01;
        } else if (path.endsWith("clamp-max")) {
            value = baseline >= 1.0 ? baseline - 0.01 : baseline + 0.01;
        } else if (path.endsWith("context-coefficient-max")) {
            value = Math.max(0.05, baseline - 0.95);
        } else if (path.endsWith("context-coefficient-min")) {
            value = 0.95;
        } else if (path.endsWith("context-factor-max")) {
            value = Math.max(0.01, baseline - 0.39);
        } else if (path.endsWith("context-factor-min")) {
            value = 0.80;
        } else if (path.contains("work-rate.instructions.") && path.endsWith("engagement")) {
            value = baseline >= 1.0 ? 0.01 : baseline * 1.10;
        } else if (path.endsWith("total-context-max")) {
            value = Math.max(0.10, baseline - 0.50);
        } else if (path.endsWith("total-context-min")) {
            value = 0.90;
        } else if (path.contains("familiarity") || path.contains("probability")) {
            value = baseline >= 0.95 ? Math.max(0.01, baseline - 0.05) : Math.min(0.95, baseline + 0.05);
        } else if (baseline == 0.0) {
            value = 0.01;
        } else {
            value = baseline * 1.10;
        }
        if (leaf.type() == CanonicalScoringWeightKey.Type.INTEGER) return Math.rint(value);
        return value;
    }
}
