package com.footballmanagergamesimulator.compartment.calibration;

import java.util.List;

public record CalibrationReadinessReport(Status status, long sampleCount, List<String> violations) {
    public CalibrationReadinessReport {
        status = status == null ? Status.FAIL : status;
        violations = List.copyOf(violations == null ? List.of() : violations);
    }

    public enum Status {
        INSUFFICIENT_DATA,
        PASS,
        FAIL
    }
}
