package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Objects;

/** Runtime boundary input for one already-loaded first-XI slot. */
public record RuntimeLineupSlot(
        Human player,
        PlayerSkills skills,
        FormationData formationData,
        PlayerPosition usedPosition,
        int occurrence) {

    public RuntimeLineupSlot {
        player = Objects.requireNonNull(player, "player");
        skills = Objects.requireNonNull(skills, "skills");
        usedPosition = Objects.requireNonNull(usedPosition, "usedPosition");
        if (player.getId() <= 0) throw new IllegalArgumentException("player id must be positive");
        if (skills.getPlayerId() != player.getId()) {
            throw new IllegalArgumentException("skills player id does not match player");
        }
        if (occurrence < 1) throw new IllegalArgumentException("occurrence must be >= 1");
        if (formationData != null && formationData.getPlayerId() != 0
                && formationData.getPlayerId() != player.getId()) {
            throw new IllegalArgumentException("formation player id does not match player");
        }
    }
}
