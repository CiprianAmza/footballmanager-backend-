package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.AwardService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/awards")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class AwardController {

    private final AwardService awardService;

    public AwardController(AwardService awardService) {
        this.awardService = awardService;
    }

    @GetMapping("/history/{awardType}")
    public Map<String, Object> history(@PathVariable String awardType) {
        return awardService.getAwardHistory(awardType);
    }

    @GetMapping("/player/{playerId}")
    public Map<String, Object> playerMajorAwards(@PathVariable long playerId) {
        return awardService.getPlayerMajorAwardSummary(playerId);
    }

    @GetMapping("/centre/global")
    public Map<String, Object> globalAwardCentre() {
        return awardService.getGlobalAwardCentre();
    }

    @GetMapping("/centre/competition/{competitionId}")
    public Map<String, Object> competitionAwardCentre(@PathVariable long competitionId) {
        return awardService.getCompetitionAwardCentre(competitionId);
    }
}
