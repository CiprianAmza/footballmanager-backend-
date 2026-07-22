package com.footballmanagergamesimulator.animation;

/** Visual event inside a replay. */
public record AnimationEvent(int frame, String type, long fromPlayerId, long toPlayerId) {
}
