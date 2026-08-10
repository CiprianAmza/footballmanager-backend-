package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.SquadInsightService;
import com.footballmanagergamesimulator.user.TeamAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/squad-insights")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class SquadInsightController {

    private final SquadInsightService squadInsightService;
    private final TeamAccessGuard teamAccessGuard;

    public SquadInsightController(SquadInsightService squadInsightService, TeamAccessGuard teamAccessGuard) {
        this.squadInsightService = squadInsightService;
        this.teamAccessGuard = teamAccessGuard;
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<?> overview(HttpServletRequest request, @PathVariable long teamId) {
        if (!teamAccessGuard.canAccessTeam(request, teamId)) {
            return ResponseEntity.status(403).body("You may only view private squad insights for your own team.");
        }
        try {
            return ResponseEntity.ok(squadInsightService.overview(teamId));
        } catch (java.util.NoSuchElementException missingTeam) {
            return ResponseEntity.notFound().build();
        }
    }
}
