package com.footballmanagergamesimulator.animation;

import java.util.List;

/** One immutable frame; position order matches AnimationReplay.players. */
public record AnimationFrame(PitchPoint ball, long ballCarrierId, List<PitchPoint> positions) {
    public AnimationFrame {
        if (ball == null || positions == null) throw new IllegalArgumentException("frame data is required");
        positions = List.copyOf(positions);
    }
}
