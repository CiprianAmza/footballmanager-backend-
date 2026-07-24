package com.footballmanagergamesimulator.chairman.mandate;

import java.util.Comparator;
import java.util.List;

/** Immutable runtime snapshot of the canonical Chairman mandate. */
public record EffectiveChairmanMandate(String requiredFormation, List<Slot> lockedSlots) {
    public record Slot(int positionIndex, long playerId) { }

    public EffectiveChairmanMandate {
        lockedSlots = lockedSlots == null ? List.of() : lockedSlots.stream()
                .sorted(Comparator.comparingInt(Slot::positionIndex).thenComparingLong(Slot::playerId))
                .toList();
    }

    public static EffectiveChairmanMandate absent() {
        return new EffectiveChairmanMandate(null, List.of());
    }
}
