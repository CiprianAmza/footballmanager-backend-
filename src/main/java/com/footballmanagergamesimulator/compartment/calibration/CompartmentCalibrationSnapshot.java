package com.footballmanagergamesimulator.compartment.calibration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CompartmentCalibrationSnapshot(
        long sampleCount,
        double legacyMeanHomeGoals,
        double legacyMeanAwayGoals,
        double legacyMeanTotalGoals,
        double canonicalMeanHomeXg,
        double canonicalMeanAwayXg,
        double canonicalMeanTotalXg,
        double legacyHomeWinRate,
        double legacyDrawRate,
        double legacyAwayWinRate,
        double canonicalHomeWinProbability,
        double canonicalDrawProbability,
        double canonicalAwayWinProbability,
        double multiclassBrierScore,
        double logarithmicLoss,
        long favoriteDecidedMatches,
        long observedUpsets,
        double observedUpsetRate,
        double expectedConditionalUpsetRate,
        Map<Integer, Long> legacyTotalGoalsHistogram,
        Map<Integer, Double> canonicalExpectedTotalGoalsHistogram,
        CalibrationSegmentSnapshot defensiveMentality,
        CalibrationSegmentSnapshot nonDefensiveMentality,
        CalibrationSegmentSnapshot stayForwardPresent,
        CalibrationSegmentSnapshot stayForwardAbsent,
        double meanTotalDurationNanos,
        long maxTotalDurationNanos) {
    public CompartmentCalibrationSnapshot {
        legacyTotalGoalsHistogram = immutableLongMap(legacyTotalGoalsHistogram);
        canonicalExpectedTotalGoalsHistogram = immutableDoubleMap(canonicalExpectedTotalGoalsHistogram);
    }

    private static Map<Integer, Long> immutableLongMap(Map<Integer, Long> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    private static Map<Integer, Double> immutableDoubleMap(Map<Integer, Double> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
    }
}
