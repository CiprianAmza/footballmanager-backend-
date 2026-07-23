package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;

import java.util.Map;

/**
 * Immutable, JPA-free snapshot of one player as seen by the compartment adapter boundary.
 *
 * <p>This record is the sole hand-off object between the mutable game domain and the pure
 * contextual-rating contract. It carries only plain scalar values and a defensively copied
 * attribute map &mdash; never a JPA entity, lazy collection, repository or mutable tactic object.
 * Once constructed it is fully immutable, so a later mutation of the source {@code Human} or
 * {@code PlayerSkills} entity cannot leak into an evaluation.
 *
 * <p>Field sourcing and legacy/missing semantics are documented in
 * {@code docs/compartment-engine/COMPARTMENT_ENGINE_V1_PHASE_2_ADAPTER.md}. Nullable fields are
 * resolved to documented defaults by {@link CompartmentDomainAdapter}, not here.
 *
 * @param playerId            canonical player id (informational; not used by the pure formula)
 * @param usedPosition        fine position key played in this slot (config key, e.g. {@code "AMC"}); nullable/legacy
 * @param naturalPosition     player's natural position ({@code Human.position}); informational, nullable
 * @param roleDisplayName     role display name (e.g. {@code "Poacher"}); nullable/unknown ⇒ neutral role
 * @param dutyLabel           duty label as stored on the lineup ({@code "Attack"/"Support"/"Defend"}); nullable
 * @param attributes          faithful copy of the mapped 1&ndash;20 attributes; defensively copied
 * @param fitness             player fitness/condition on a 0&ndash;100 scale
 * @param morale              player morale on a 0&ndash;100 scale
 * @param positionFamiliarity optional canonical familiarity factor in [0,1]; {@code null} ⇒ documented default
 * @param roleSuitability     optional canonical role suitability on 0&ndash;100; {@code null} ⇒ documented default
 */
public record DomainPlayerSnapshot(
        long playerId,
        String usedPosition,
        String naturalPosition,
        String roleDisplayName,
        String dutyLabel,
        Map<PlayerAttribute, Integer> attributes,
        double fitness,
        double morale,
        Double positionFamiliarity,
        Double roleSuitability) {

    public DomainPlayerSnapshot {
        // Deep-immutable, JPA-free copy. Rejecting nulls inside the map keeps the boundary honest.
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
