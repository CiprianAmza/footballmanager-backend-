package com.footballmanagergamesimulator.multiplayer;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Public, deliberately sanitised blocker vocabulary for room state. */
@Component
public class RoomBlockerResolver {
    public Map<String, Object> publicBlocker(String code) {
        if (code == null || code.isBlank()) return Map.of("code", "NONE");
        return Map.of("code", code, "message", switch (code) {
            case "JOB_OFFER" -> "A job offer is waiting in the inbox";
            case "PRESS_CONFERENCE" -> "A neutral response will be applied";
            case "LIVE_MATCH" -> "The live match will be completed by the server";
            default -> "The room could not continue automatically";
        });
    }
}
