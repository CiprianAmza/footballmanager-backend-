package com.footballmanagergamesimulator.multiplayer;

public record AdvanceClaim(Long cycleId, String token, boolean forceContinue) {
    public AdvanceClaim(Long cycleId, String token) {
        this(cycleId, token, false);
    }
}
