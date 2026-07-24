package com.footballmanagergamesimulator.chairman.mandate;

import java.util.List;

public final class ChairmanTacticalMandateDtos {
    private ChairmanTacticalMandateDtos() { }

    public record LockedSlot(int positionIndex, long playerId) { }
    public record UpdateRequest(String requiredFormation, List<LockedSlot> lockedSlots, long expectedVersion) { }
    public record MandateView(long teamId, String requiredFormation, List<LockedSlot> lockedSlots,
                              long version, long updatedByProfileId, int updatedSeason, int updatedGameDay) { }
}
