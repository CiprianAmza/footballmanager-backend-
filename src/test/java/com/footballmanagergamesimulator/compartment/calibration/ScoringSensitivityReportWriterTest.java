package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringSensitivityReportWriterTest {
    @Test
    void reportOrderIsAbsoluteDeltaThenKey() throws Exception {
        Path target = Path.of("target", "compartment-calibration", "writer-test");
        new ScoringSensitivityReportWriter().write(target, List.of(
                result("z", 1), result("a", -2), result("b", 2)));
        String csv = Files.readString(target.resolve("sensitivity.csv"));
        assertThat(csv.indexOf("a," )).isLessThan(csv.indexOf("b,"));
        assertThat(csv.indexOf("b," )).isLessThan(csv.indexOf("z,"));
    }
    private static ScoringSensitivityResult result(String key, double delta) {
        return new ScoringSensitivityResult(key, 1, 1, 60 + delta, delta, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
    }
}
