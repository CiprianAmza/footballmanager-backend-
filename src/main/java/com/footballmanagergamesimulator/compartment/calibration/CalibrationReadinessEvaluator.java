package com.footballmanagergamesimulator.compartment.calibration;

import java.util.ArrayList;
import java.util.List;

public final class CalibrationReadinessEvaluator {
    public CalibrationReadinessReport evaluate(CompartmentCalibrationSnapshot snapshot,
                                                CalibrationThresholds thresholds) {
        if (snapshot == null || thresholds == null) {
            throw new NullPointerException("snapshot and thresholds are required");
        }
        List<String> violations = new ArrayList<>();
        if (snapshot.sampleCount() < thresholds.minimumSamples()) {
            violations.add("sample count below minimum");
            return new CalibrationReadinessReport(CalibrationReadinessReport.Status.INSUFFICIENT_DATA,
                    snapshot.sampleCount(), violations);
        }

        double homeGoalsDelta = Math.abs(snapshot.legacyMeanHomeGoals() - snapshot.canonicalMeanHomeXg());
        double awayGoalsDelta = Math.abs(snapshot.legacyMeanAwayGoals() - snapshot.canonicalMeanAwayXg());
        double homeOutcomeDelta = Math.abs(snapshot.legacyHomeWinRate() - snapshot.canonicalHomeWinProbability());
        double drawOutcomeDelta = Math.abs(snapshot.legacyDrawRate() - snapshot.canonicalDrawProbability());
        double awayOutcomeDelta = Math.abs(snapshot.legacyAwayWinRate() - snapshot.canonicalAwayWinProbability());
        double upsetDelta = Math.abs(snapshot.observedUpsetRate() - snapshot.expectedConditionalUpsetRate());

        if (homeGoalsDelta > thresholds.maximumMeanHomeGoalsDelta()) violations.add("home goals delta above maximum");
        if (awayGoalsDelta > thresholds.maximumMeanAwayGoalsDelta()) violations.add("away goals delta above maximum");
        if (homeOutcomeDelta > thresholds.maximumOutcomeRateDelta()) violations.add("home outcome delta above maximum");
        if (drawOutcomeDelta > thresholds.maximumOutcomeRateDelta()) violations.add("draw outcome delta above maximum");
        if (awayOutcomeDelta > thresholds.maximumOutcomeRateDelta()) violations.add("away outcome delta above maximum");
        if (upsetDelta > thresholds.maximumUpsetRateDelta()) violations.add("upset delta above maximum");
        if (snapshot.multiclassBrierScore() > thresholds.maximumMulticlassBrierScore()) {
            violations.add("Brier score above maximum");
        }
        return new CalibrationReadinessReport(
                violations.isEmpty() ? CalibrationReadinessReport.Status.PASS : CalibrationReadinessReport.Status.FAIL,
                snapshot.sampleCount(), violations);
    }
}
