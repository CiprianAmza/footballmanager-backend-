package com.footballmanagergamesimulator.animation;

import java.util.List;

/**
 * One rendered frame. Coordinates are 0–100 on both axes (x: left goal line →
 * right goal line, y: bottom sideline → top sideline), rounded to 0.1.
 *
 * @param ballCarrierId playerId in possession; 0 while the ball is in flight
 *                      or dead at a set-piece spot
 * @param positions     [x, y] per player, same order as the replay's player list
 */
public record ReplayFrame(
        double ballX,
        double ballY,
        long ballCarrierId,
        List<double[]> positions) {
}
