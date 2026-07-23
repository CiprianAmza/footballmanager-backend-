package com.footballmanagergamesimulator.animation;

import java.util.List;

/** Compact persistent regeneration contract. */
public record AnimationRecipe(
        String fixtureKey,
        int slotIndex,
        long planSeed,
        long seed,
        int generatorVersion,
        PatternId pattern,
        int minute,
        int firstHalfStoppage,
        MatchPeriod period,
        long scoringTeamId,
        long defendingTeamId,
        long homeTeamId,
        AnimationPhase phase,
        AnimationOutcome outcome,
        long scorerId,
        Long assisterId,
        List<PlayerSnapshot> playersOnPitch,
        TacticalContext tacticalContext,
        AnimationPhysicsProfile physicsProfile) {

    public AnimationRecipe {
        if (pattern == null || physicsProfile == null) throw new IllegalArgumentException("pattern/profile required");
        playersOnPitch = playersOnPitch == null ? null : List.copyOf(playersOnPitch);
        MatchMomentSpec spec = new MatchMomentSpec(fixtureKey, slotIndex, planSeed, generatorVersion,
                minute, firstHalfStoppage, period, scoringTeamId, defendingTeamId, homeTeamId,
                phase, outcome, scorerId, assisterId, playersOnPitch, tacticalContext);
        long expected = AnimationSeed.derive(planSeed, fixtureKey, slotIndex, generatorVersion);
        if (seed != expected) throw new IllegalArgumentException("recipe seed does not match " + spec.key());
    }

    public MatchMomentSpec toSpec() {
        return new MatchMomentSpec(fixtureKey, slotIndex, planSeed, generatorVersion,
                minute, firstHalfStoppage, period, scoringTeamId, defendingTeamId, homeTeamId,
                phase, outcome, scorerId, assisterId, playersOnPitch, tacticalContext);
    }

    public AnimationKey key() {
        return new AnimationKey(fixtureKey, slotIndex);
    }
}
