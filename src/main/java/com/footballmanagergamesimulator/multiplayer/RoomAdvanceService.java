package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.service.GameAdvanceService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class RoomAdvanceService {
    private final GameAdvanceService gameAdvanceService;
    public RoomAdvanceService(GameAdvanceService gameAdvanceService) { this.gameAdvanceService = gameAdvanceService; }
    public void advanceOneDay(int season, Set<Integer> roomUserIds) {
        gameAdvanceService.advanceOneDayUnattended(season, roomUserIds);
    }
}
