package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.AssistantManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/assistant")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class AssistantManagerController {

    @Autowired
    private AssistantManagerService assistantManagerService;

    @GetMapping("/suggestFormation/{teamId}")
    public Map<String, Object> suggestFormation(@PathVariable long teamId) {
        return assistantManagerService.suggestBestFormation(teamId);
    }

    @GetMapping("/lineupConcerns/{teamId}")
    public Map<String, Object> lineupConcerns(@PathVariable long teamId) {
        return assistantManagerService.suggestLineupChanges(teamId);
    }

    @GetMapping("/analyzeOpponent/{teamId}/{opponentTeamId}")
    public Map<String, Object> analyzeOpponent(@PathVariable long teamId, @PathVariable long opponentTeamId) {
        return assistantManagerService.analyzeOpponent(teamId, opponentTeamId);
    }

    @GetMapping("/transferNeeds/{teamId}")
    public Map<String, Object> transferNeeds(@PathVariable long teamId) {
        return assistantManagerService.suggestTransferTargets(teamId);
    }

    @GetMapping("/preMatchBriefing/{teamId}/{opponentTeamId}")
    public Map<String, Object> preMatchBriefing(@PathVariable long teamId, @PathVariable long opponentTeamId) {
        return assistantManagerService.getPreMatchBriefing(teamId, opponentTeamId);
    }
}
