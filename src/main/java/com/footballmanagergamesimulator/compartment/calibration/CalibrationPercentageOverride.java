package com.footballmanagergamesimulator.compartment.calibration;

/** Converts a requested relative percentage into a concrete legal candidate value. */
public final class CalibrationPercentageOverride {
    private CalibrationPercentageOverride() { }

    public static CanonicalScoringWeightOverride create(CanonicalScoringWeightKey leaf,
                                                         double requestedPercent) {
        if (leaf == null) throw new NullPointerException("leaf");
        if (leaf.type() == CanonicalScoringWeightKey.Type.DISCRETE) {
            throw new IllegalArgumentException("discrete controls cannot be percentage-calibrated: " + leaf.path());
        }
        if (!Double.isFinite(requestedPercent) || requestedPercent == 0.0 || requestedPercent <= -100.0) {
            throw new IllegalArgumentException("requested percentage must be finite, non-zero, and greater than -100");
        }
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        double candidate;
        if (baseline == 0.0) {
            double neighbour = Math.abs(CanonicalWeightPerturbation.validAlternative(leaf));
            candidate = Math.copySign(neighbour, requestedPercent);
        } else {
            candidate = baseline * (1.0 + requestedPercent / 100.0);
        }
        if (leaf.type() == CanonicalScoringWeightKey.Type.INTEGER) {
            candidate = Math.rint(candidate);
            if (candidate == baseline) candidate += Math.copySign(1.0, requestedPercent);
        }
        String path = leaf.path();
        if (path.endsWith("attribute-min") || path.endsWith("attribute-max")) {
            candidate = Math.max(1.0, Math.min(20.0, candidate));
        } else if (path.endsWith("transfer-share") || path.endsWith("midfield-to-attack")) {
            candidate = Math.max(0.0, Math.min(1.0, candidate));
        }
        if (candidate == baseline) {
            throw new NoLegalDirectionException("requested direction has no legal room at the configured boundary");
        }
        return new CanonicalScoringWeightOverride(leaf.path(), candidate);
    }

    public static double actualPercent(CanonicalScoringWeightKey leaf, double testedValue) {
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        if (baseline == 0.0) return Double.NaN;
        return 100.0 * (testedValue - baseline) / Math.abs(baseline);
    }

    public static final class NoLegalDirectionException extends IllegalArgumentException {
        public NoLegalDirectionException(String message) { super(message); }
    }
}
