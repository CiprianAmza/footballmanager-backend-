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

    /**
     * How the toucher gained the ball from the previous toucher.
     *
     * <ul>
     *   <li>{@link #PASS} — a clean pass from a team-mate (the previous toucher).
     *       This is the only kind that can represent an assist into the scorer.</li>
     *   <li>{@link #CARRY} — the same player keeps the ball and dribbles; no
     *       possession change and no discrete event.</li>
     *   <li>{@link #LOOSE} — the ball was not cleanly delivered to this player: a
     *       loose ball, rebound, deflection or turnover. Never a clean assist.</li>
     * </ul>
     */
    public enum ReceiveKind { PASS, CARRY, LOOSE }

    public record Touch(long playerId, PitchPoint target, int dwellFrames,
                        double arrivalBend, ReceiveKind receiveKind) {
        public Touch {
            if (playerId <= 0 || target == null || dwellFrames < 0)
                throw new IllegalArgumentException("invalid scripted touch");
            if (receiveKind == null) throw new IllegalArgumentException("receiveKind is required");
        }

        public Touch(long playerId, PitchPoint target, int dwellFrames, double arrivalBend) {
            this(playerId, target, dwellFrames, arrivalBend, ReceiveKind.PASS);
        }
    }
}
