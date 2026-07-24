package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class CalibrationShardTest {
    @Test
    void partitionsCatalogOrderDeterministically() {
        var values = List.of("a", "b", "c", "d", "e");
        assertThat(CalibrationShard.select(values, new CalibrationShard(0, 2))).containsExactly("a", "c", "e");
        assertThat(CalibrationShard.select(values, new CalibrationShard(1, 2))).containsExactly("b", "d");
    }

    @Test
    void validatesShardBounds() {
        assertThatThrownBy(() -> new CalibrationShard(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalibrationShard(2, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalibrationShard(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
