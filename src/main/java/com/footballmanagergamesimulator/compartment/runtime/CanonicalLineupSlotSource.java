package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Objects;

/** Domain-backed lineup slot converted at the authoritative runtime boundary. */
public record CanonicalLineupSlotSource(
        Human player,
        PlayerSkills skills,
        FormationData formationData,
        String usedPosition,
        int occurrence) {
    public CanonicalLineupSlotSource {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(skills, "skills");
        if (usedPosition == null || usedPosition.isBlank()) {
            throw new IllegalArgumentException("usedPosition must not be blank");
        }
        if (occurrence <= 0) throw new IllegalArgumentException("occurrence must be positive");
    }

    public RuntimeLineupSlot toRuntimeSlot() {
        return new RuntimeLineupSlot(
                player, skills, formationData, PlayerPosition.require(usedPosition), occurrence);
    }
}
