package com.footballmanagergamesimulator.animation;

import java.util.List;

/** Declarative ball/touch route produced by one pattern. */
public record PlayScript(
        PatternId pattern,
        List<Touch> touches,
        PitchPoint deadBallSpot,
        int preludeFrames,
        double shotBend) {

    public PlayScript {
        if (pattern == null || touches == null || touches.isEmpty())
            throw new IllegalArgumentException("pattern and touches are required");
        touches = List.copyOf(touches);
        if (preludeFrames < 0) throw new IllegalArgumentException("preludeFrames must be non-negative");
    }

    public record Touch(long playerId, PitchPoint target, int dwellFrames, double arrivalBend) {
        public Touch {
            if (playerId <= 0 || target == null || dwellFrames < 0)
                throw new IllegalArgumentException("invalid scripted touch");
        }
    }
}
