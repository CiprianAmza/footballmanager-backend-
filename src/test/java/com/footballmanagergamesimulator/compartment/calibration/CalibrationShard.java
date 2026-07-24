package com.footballmanagergamesimulator.compartment.calibration;

import java.util.List;

/** Deterministic, restartable partition of the lexicographically ordered catalog. */
record CalibrationShard(int index, int count) {
    CalibrationShard {
        if (count < 1) throw new IllegalArgumentException("shard-count must be >= 1");
        if (index < 0 || index >= count) throw new IllegalArgumentException("shard-index out of range");
    }

    static CalibrationShard fromSystemProperties() {
        int count = Integer.getInteger("compartment.calibration.shard-count", 1);
        int index = Integer.getInteger("compartment.calibration.shard-index", 0);
        return new CalibrationShard(index, count);
    }

    boolean owns(int catalogIndex) { return catalogIndex % count == index; }

    static <T> List<T> select(List<T> values, CalibrationShard shard) {
        return java.util.stream.IntStream.range(0, values.size()).filter(shard::owns)
                .mapToObj(values::get).toList();
    }
}
