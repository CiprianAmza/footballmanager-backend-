package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.squad.MatchBonusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The extra bonus for the next match (design DESIGN_START_CARIERA §6.4), shown on the Today page. */
@RestController
@RequestMapping("/match-bonus")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class MatchBonusController {

    private final MatchBonusService bonuses;
    private final ClubActionAuthorizationService authorization;

    public MatchBonusController(MatchBonusService bonuses, ClubActionAuthorizationService authorization) {
        this.bonuses = bonuses;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public MatchBonusService.Status status(@PathVariable long teamId) {
        return bonuses.status(teamId);
    }

    /** Body: {"teamId": 1}. Ask the chairman. */
    @PostMapping("/request")
    public ResponseEntity<?> request(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(bonuses.request(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }

    /** Body: {"teamId": 1}. Pay it out of the manager's own money. */
    @PostMapping("/pay-yourself")
    public ResponseEntity<?> payYourself(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(bonuses.payYourself(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }
}
