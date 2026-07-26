package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.service.GameAdvanceService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class RoomAdvanceService {
    private final GameAdvanceService gameAdvanceService;
    public RoomAdvanceService(GameAdvanceService gameAdvanceService) { this.gameAdvanceService = gameAdvanceService; }
    public RoomAdvanceResult advanceOneDay(int expectedSeason, int expectedDay,
                                           Set<Integer> roomUserIds, boolean forceContinue) {
        Map<String, Object> result = gameAdvanceService.advanceOneDayUnattended(
                expectedSeason, expectedDay, roomUserIds, forceContinue);
        RoomAdvanceResult.Status status = RoomAdvanceResult.Status.valueOf(
                String.valueOf(result.getOrDefault("roomAdvanceStatus", "ADVANCED")));
        return new RoomAdvanceResult(status,
                ((Number) result.getOrDefault("season", expectedSeason)).intValue(),
                ((Number) result.getOrDefault("day", expectedDay)).intValue(),
                (String) result.get("blockerCode"), (String) result.get("blockerMessage"));
    }
}
