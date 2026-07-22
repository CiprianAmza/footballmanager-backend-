package com.footballmanagergamesimulator.animation;

import java.util.List;

/**
 * The rendered animation for one canonical moment: player metadata (attacking
 * side first, then defending side — frame position order matches) plus 151
 * frames and the visual event list. Identity, minute, scorer and assist are
 * copied verbatim from the {@link MatchMomentSpec}; nothing here can disagree
 * with the canonical event.
 *
 * @param scoringTeamAttacksRight orientation after half-aware mirroring; the
 *                                frontend draws the goals accordingly
 * @param renderedWithVersion     exact frozen generator version that rendered
 *                                the frames; unsupported versions fail rather
 *                                than being silently substituted
 */
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
        String patternId,
        int renderedWithVersion,
        long scorerId,
        Long assisterId,
        boolean homeAttacksRight,
        boolean scoringTeamAttacksRight,
        List<PlayerSnapshot> players,
        List<ReplayFrame> frames,
        List<ReplayEvent> events) {

    public AnimationKey key() {
        return new AnimationKey(fixtureKey, slotIndex);
    }

    public int totalFrames() {
        return frames.size() - 1;
    }

    /**
     * Order-sensitive content hash over frames, events and player order.
     * Two replays with the same fingerprint are (for testing purposes)
     * frame-identical; determinism tests also compare values exactly.
     */
    public long fingerprint() {
        long h = AnimationSeeds.fnv1a64(fixtureKey + "#" + slotIndex + "#" + patternId);
        for (PlayerSnapshot p : players) h = AnimationSeeds.mix(h ^ p.playerId());
        for (ReplayFrame f : frames) {
            h = AnimationSeeds.mix(h ^ Double.doubleToLongBits(f.ballX()));
            h = AnimationSeeds.mix(h ^ Double.doubleToLongBits(f.ballY()));
            h = AnimationSeeds.mix(h ^ f.ballCarrierId());
            for (double[] pos : f.positions()) {
                h = AnimationSeeds.mix(h ^ Double.doubleToLongBits(pos[0]));
                h = AnimationSeeds.mix(h ^ Double.doubleToLongBits(pos[1]));
            }
        }
        for (ReplayEvent e : events) {
            h = AnimationSeeds.mix(h ^ (e.frame() * 1_000_003L));
            h = AnimationSeeds.mix(h ^ AnimationSeeds.fnv1a64(e.type()));
            h = AnimationSeeds.mix(h ^ e.fromPlayerId());
            h = AnimationSeeds.mix(h ^ e.toPlayerId());
        }
        return h;
    }
}
