package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Map;
import java.util.Objects;

/**
 * Builds an immutable {@link DomainPlayerSnapshot} from the real, already-loaded game entities and
 * DTOs. This is the only class in the adapter package that references the mutable domain types
 * ({@link Human}, {@link PlayerSkills}, {@link FormationData}); it copies scalar values out so no
 * JPA entity or lazy state ever reaches the snapshot or the pure calculator.
 *
 * <p>It performs no repository, database or Spring access &mdash; callers hand in the entities they
 * already hold. Position, role and duty are copied verbatim from the domain; the adapter applies
 * the typed mapping and documented defaults.
 */
public final class DomainSnapshotFactory {

    private DomainSnapshotFactory() {}

    /**
     * Snapshot from the primitive domain sources.
     *
     * @param player               the player entity (required)
     * @param skills               the player's attribute row (required)
     * @param usedPosition         the fine position key played in this slot (nullable/legacy)
     * @param roleDisplayName      role display name for this slot (nullable)
     * @param dutyLabel            duty label for this slot ({@code "Attack"/"Support"/"Defend"}, nullable)
     * @param positionFamiliarity  optional canonical familiarity factor in [0,1] ({@code null} ⇒ default)
     * @param roleSuitability      optional canonical role suitability on 0&ndash;100 ({@code null} ⇒ default)
     */
    public static DomainPlayerSnapshot fromDomain(Human player,
                                                  PlayerSkills skills,
                                                  String usedPosition,
                                                  String roleDisplayName,
                                                  String dutyLabel,
                                                  Double positionFamiliarity,
                                                  Double roleSuitability) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(skills, "skills");
        Map<PlayerAttribute, Integer> attributes = PlayerAttributeMapping.rawAttributeMap(skills);
        return new DomainPlayerSnapshot(
                player.getId(),
                usedPosition,
                player.getPosition(),
                roleDisplayName,
                dutyLabel,
                attributes,
                player.getFitness(),
                player.getMorale(),
                positionFamiliarity,
                roleSuitability);
    }

    /**
     * Snapshot from a lineup slot DTO plus the player and skills. Role, duty and (unused here)
     * instructions are read from the {@link FormationData} slot; the fine used position is resolved
     * by the caller because {@code FormationData} carries only a grid index.
     */
    public static DomainPlayerSnapshot fromLineupSlot(Human player,
                                                      PlayerSkills skills,
                                                      FormationData slot,
                                                      String usedPosition,
                                                      Double positionFamiliarity,
                                                      Double roleSuitability) {
        String role = slot == null ? null : slot.getRole();
        String duty = slot == null ? null : slot.getDuty();
        return fromDomain(player, skills, usedPosition, role, duty, positionFamiliarity, roleSuitability);
    }

    /**
     * Convenience overload without canonical familiarity/suitability. Both resolve to the adapter's
     * documented neutral defaults; use the explicit overloads once a runtime supplies canonical
     * familiarity and role suitability.
     */
    public static DomainPlayerSnapshot fromDomain(Human player,
                                                  PlayerSkills skills,
                                                  String usedPosition,
                                                  String roleDisplayName,
                                                  String dutyLabel) {
        return fromDomain(player, skills, usedPosition, roleDisplayName, dutyLabel, null, null);
    }
}
