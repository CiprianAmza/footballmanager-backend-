package com.footballmanagergamesimulator.compartment.shadow;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Objects;

public record ShadowLineupSlotSource(
        Human player,
        PlayerSkills skills,
        FormationData formationData,
        String usedPosition,
        int occurrence) {
    public ShadowLineupSlotSource {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(skills, "skills");
        if (usedPosition == null || usedPosition.isBlank()) throw new IllegalArgumentException("usedPosition must not be blank");
        if (occurrence <= 0) throw new IllegalArgumentException("occurrence must be positive");
    }

    public RuntimeLineupSlot toRuntimeSlot() {
        return new RuntimeLineupSlot(player, skills, formationData, PlayerPosition.require(usedPosition), occurrence);
    }
}
