package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.Objects;

/** Pure bridge from canonical Phase 5 capability context to the existing rating adapter. */
public final class CanonicalPlayerContextAdapter {
    private final CompartmentDomainAdapter domainAdapter;
    private final PlayerCapabilityResolver capabilityResolver;

    public CanonicalPlayerContextAdapter(CompartmentEngineConfig compartmentConfig,
                                         MatchEngineConfig matchEngineConfig) {
        this.domainAdapter = new CompartmentDomainAdapter(Objects.requireNonNull(
                compartmentConfig, "compartmentConfig"));
        this.capabilityResolver = new PlayerCapabilityResolver(matchEngineConfig);
    }

    public CanonicalPlayerEvaluation evaluate(CanonicalLineupPlayer player,
                                               TacticalContextInput tacticalContext) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tacticalContext, "tacticalContext");

        int positionFamiliarity = capabilityResolver.positionFamiliarityOrFallback(
                player.capability(), player.usedPosition());
        double positionFactor = positionFamiliarity / 20.0;
        int roleFamiliarity = capabilityResolver.roleFamiliarityOrFallback(
                player.capability(), player.usedPosition(), player.role());
        String primaryPosition = player.capability().primaryPositionOptional()
                .map(position -> position.code())
                .orElse(null);
        String roleDisplayName = player.role() == null ? "" : player.role().displayName();
        DomainPlayerSnapshot snapshot = new DomainPlayerSnapshot(
                player.playerId(),
                player.usedPosition().code(),
                primaryPosition,
                roleDisplayName,
                player.duty().name(),
                player.attributes(),
                player.fitness(),
                player.morale(),
                positionFactor,
                player.roleSuitability());
        ContextualPlayerRating rating = domainAdapter.rate(snapshot, tacticalContext);
        return new CanonicalPlayerEvaluation(
                player.playerId(),
                player.usedPosition(),
                player.occurrence(),
                player.role(),
                player.duty(),
                positionFamiliarity,
                positionFactor,
                roleFamiliarity,
                player.roleSuitability(),
                player.capability().leftFootRating(),
                player.capability().rightFootRating(),
                player.capability().positionFallbackUsed()
                        || !player.capability().positionFamiliarity().containsKey(player.usedPosition()),
                player.role() == null
                        || player.capability().roleFallbackUsed()
                        || !player.capability().roleFamiliarity().containsKey(
                                player.role() == null ? null : new PositionRoleKey(player.usedPosition(), player.role())),
                player.capability().footFallbackUsed(),
                rating);
    }
}
