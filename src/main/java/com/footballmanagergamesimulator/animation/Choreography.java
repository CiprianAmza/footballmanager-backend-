package com.footballmanagergamesimulator.animation;

import java.util.List;

/**
 * A pattern's declarative plan for the phase, in canonical orientation
 * (attacking team plays toward x=100). The pattern only proposes the ball
 * route and rough spatial shape; the {@link FrameCompiler} owns timing,
 * physics, the outcome geometry and every invariant.
 *
 * <p>Chain semantics: {@code chain.get(0)} starts with the ball (or takes the
 * set piece); each subsequent step receives a pass at roughly its (x, y);
 * the LAST step is always the scorer/shooter, and when the spec carries an
 * assister the penultimate step is always the assister. The same player may
 * appear more than once (e.g. a one-two).
 *
 * @param setPieceSpot  dead-ball spot ([x, y]) for PENALTY/FREE_KICK/CORNER
 *                      phases, {@code null} for open play
 * @param preKickFrames frames of dead ball / run-up before the first touch
 *                      (set pieces only)
 * @param shotCurve     lateral curvature of the final shot's Bézier flight
 */
public record Choreography(
        String patternId,
        List<ChainStep> chain,
        double[] setPieceSpot,
        int preKickFrames,
        double shotCurve) {

    /**
     * One node of the ball route.
     *
     * @param dwellFrames frames the player keeps the ball before releasing it
     * @param passCurve   lateral curvature of the pass ARRIVING at this step
     *                    (ignored for the first step)
     */
    public record ChainStep(long playerId, double x, double y, int dwellFrames, double passCurve) {
    }

    public ChainStep scorerStep() {
        return chain.get(chain.size() - 1);
    }
}
