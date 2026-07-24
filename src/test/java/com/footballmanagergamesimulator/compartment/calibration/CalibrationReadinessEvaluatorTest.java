package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationReadinessEvaluatorTest {
    @Test
    void insufficientDataIsReportedBeforeMetricChecks() {
        CompartmentCalibrationSnapshot snapshot = snapshot(3, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false);
        CalibrationReadinessReport report = new CalibrationReadinessEvaluator().evaluate(snapshot,
                new CalibrationThresholds(10, 0.1, 0.1, 0.1, 0.1, 0.1));

        assertThat(report.status()).isEqualTo(CalibrationReadinessReport.Status.INSUFFICIENT_DATA);
        assertThat(report.violations()).containsExactly("sample count below minimum");
    }

    @Test
    void failuresUseTheContractOrderAndPassHasNoViolations() {
        CalibrationReadinessEvaluator evaluator = new CalibrationReadinessEvaluator();
        CalibrationReadinessReport fail = evaluator.evaluate(
                snapshot(10, 1.0, 1.0, 0.5, 0.5, 0.5, 0.9, 0.5, false),
                new CalibrationThresholds(10, 0.1, 0.1, 0.1, 0.1, 0.1));
        assertThat(fail.status()).isEqualTo(CalibrationReadinessReport.Status.FAIL);
        assertThat(fail.violations()).containsExactly(
                "home goals delta above maximum", "away goals delta above maximum",
                "home outcome delta above maximum", "draw outcome delta above maximum",
                "away outcome delta above maximum", "upset delta above maximum",
                "Brier score above maximum");

        CalibrationReadinessReport pass = evaluator.evaluate(
                snapshot(10, 0.01, 0.01, 0.34, 0.33, 0.33, 0.02, 0.10, true),
                new CalibrationThresholds(10, 0.1, 0.1, 0.1, 0.1, 0.1));
        assertThat(pass.status()).isEqualTo(CalibrationReadinessReport.Status.PASS);
        assertThat(pass.violations()).isEmpty();
    }

    private static CompartmentCalibrationSnapshot snapshot(long samples, double homeGoals, double awayGoals,
                                                            double homeWin, double draw, double awayWin,
                                                            double upset, double brier, boolean matchingRates) {
        return new CompartmentCalibrationSnapshot(samples, matchingRates ? homeGoals : 0.0,
                matchingRates ? awayGoals : 0.0, 0.0, homeGoals, awayGoals, 0.0,
                matchingRates ? homeWin : 0.0, matchingRates ? draw : 0.0, matchingRates ? awayWin : 0.0,
                homeWin, draw, awayWin, brier, 0.0, 1, 1, upset, matchingRates ? upset : 0.0,
                new LinkedHashMap<>(), new LinkedHashMap<>(), segment(), segment(), segment(), segment(), 0.0, 0);
    }

    private static CalibrationSegmentSnapshot segment() {
        return new CalibrationSegmentSnapshot(0, 0, 0, 0, 0, 0, 0);
    }
}
