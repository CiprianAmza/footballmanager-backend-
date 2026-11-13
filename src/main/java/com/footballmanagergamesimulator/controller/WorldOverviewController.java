package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.WorldOverviewService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/overview")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class WorldOverviewController {

    private final WorldOverviewService worldOverviewService;

    public WorldOverviewController(WorldOverviewService worldOverviewService) {
        this.worldOverviewService = worldOverviewService;
    }

    @GetMapping("/team-values")
    public Map<String, Object> teamValues() {
        return worldOverviewService.teamValues();
    }

    @GetMapping("/league-best-xi/{competitionId}")
    public Map<String, Object> leagueBestEleven(@PathVariable long competitionId) {
        return worldOverviewService.leagueBestEleven(competitionId);
    }

    @GetMapping("/world-best-xi")
    public Map<String, Object> worldBestEleven() {
        return worldOverviewService.worldBestEleven();
    }
}
