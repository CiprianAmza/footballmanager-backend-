package com.footballmanagergamesimulator.animation;

import java.util.List;

/** Presentation-only result for one canonical moment. */
public record AnimationReplay(
        String fixtureKey,
        int slotIndex,
        int minute,
        int firstHalfStoppage,
        long scoringTeamId,
        long defendingTeamId,
        long homeTeamId,
        AnimationPhase phase,
        AnimationOutcome outcome,
        PatternId pattern,
        int renderedWithVersion,
        long scorerId,
        Long assisterId,
        boolean homeAttacksRight,
        boolean scoringTeamAttacksRight,
        List<PlayerSnapshot> players,
        List<AnimationFrame> frames,
        List<AnimationEvent> events) {

    public AnimationReplay {
        players = List.copyOf(players);
        frames = List.copyOf(frames);
        events = List.copyOf(events);
    }

    public AnimationKey key() {
        return new AnimationKey(fixtureKey, slotIndex);
    }

    public long fingerprint() {
        long hash = AnimationSeed.mix(AnimationSeed.derive(0, fixtureKey, slotIndex, renderedWithVersion));
        hash = AnimationSeed.mix(hash ^ minute ^ pattern.ordinal());
        for (AnimationFrame frame : frames) {
            hash = AnimationSeed.mix(hash ^ Double.doubleToLongBits(frame.ball().x()));
            hash = AnimationSeed.mix(hash ^ Double.doubleToLongBits(frame.ball().y()));
            hash = AnimationSeed.mix(hash ^ frame.ballCarrierId());
            for (PitchPoint point : frame.positions()) {
                hash = AnimationSeed.mix(hash ^ Double.doubleToLongBits(point.x()));
                hash = AnimationSeed.mix(hash ^ Double.doubleToLongBits(point.y()));
            }
        }
        return hash;
    }
}
