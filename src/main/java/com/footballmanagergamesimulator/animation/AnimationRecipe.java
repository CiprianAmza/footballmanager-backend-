package com.footballmanagergamesimulator.animation;

import java.util.List;

/**
 * Compact, persistable representation of one animation. Because the director
 * is fully deterministic, the recipe (≈2 KB of JSON) is enough to regenerate
 * the 151 frames identically — the frames themselves need not be stored.
 *
 * <p>{@code generatorVersion} pins the frame compiler + pattern library
 * revision; historical recipes keep rendering identically as long as that
 * version is still shipped (see ANIMATION_ENGINE_V2.md §3 for the retirement
 * policy).
 */
public record AnimationRecipe(
        String fixtureKey,
        int slotIndex,
        long planSeed,
        long seed,
        int generatorVersion,
        String patternId,
        AnimationPhase phase,
        AnimationOutcome outcome,
        int minute,
        int firstHalfStoppage,
        long scoringTeamId,
        long defendingTeamId,
        long homeTeamId,
        long scorerId,
        Long assisterId,
        AnimationMotionLimits motionLimits,
        List<PlayerSnapshot> attackingPlayers,
        List<PlayerSnapshot> defendingPlayers,
        TacticalContext tacticalContext) {

    public AnimationRecipe {
        if (patternId == null || patternId.isBlank()) {
            throw new IllegalArgumentException("patternId is required");
        }
        if (motionLimits == null) throw new IllegalArgumentException("motionLimits are required");
        attackingPlayers = attackingPlayers == null ? null : List.copyOf(attackingPlayers);
        defendingPlayers = defendingPlayers == null ? null : List.copyOf(defendingPlayers);

        // Reuse the canonical contract validation so corrupted persisted data
        // cannot enter the renderer with impossible teams, rosters or roles.
        new MatchMomentSpec(fixtureKey, slotIndex, planSeed, generatorVersion,
                minute, firstHalfStoppage, scoringTeamId, defendingTeamId, homeTeamId,
                phase, outcome, scorerId, assisterId,
                attackingPlayers, defendingPlayers, tacticalContext);
        long expectedSeed = AnimationSeeds.derive(planSeed, fixtureKey, slotIndex, generatorVersion);
        if (seed != expectedSeed) {
            throw new IllegalArgumentException("recipe seed does not match its canonical identity");
        }
    }

    public AnimationKey key() {
        return new AnimationKey(fixtureKey, slotIndex);
    }

    /** Rebuild the canonical spec this recipe was produced from. */
    public MatchMomentSpec toSpec() {
        return new MatchMomentSpec(fixtureKey, slotIndex, planSeed, generatorVersion,
                minute, firstHalfStoppage, scoringTeamId, defendingTeamId, homeTeamId,
                phase, outcome, scorerId, assisterId,
                attackingPlayers, defendingPlayers, tacticalContext);
    }
}
