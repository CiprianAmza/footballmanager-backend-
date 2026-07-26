package com.footballmanagergamesimulator.multiplayer;

public record RoomAdvanceResult(Status status, int season, int day, String blockerCode, String blockerMessage) {
    public enum Status { ADVANCED, ALREADY_ADVANCED, BLOCKED }
}
