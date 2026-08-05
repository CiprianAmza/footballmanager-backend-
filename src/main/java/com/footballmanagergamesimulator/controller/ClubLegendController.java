package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.ClubLegendService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/club-legends")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ClubLegendController {

    private final ClubLegendService service;
    private final ClubActionAuthorizationService authorization;

    public ClubLegendController(ClubLegendService service, ClubActionAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/team/{teamId}")
    public List<ClubLegendService.ClubLegendView> list(@PathVariable long teamId) {
        return service.list(teamId);
    }

    @PostMapping("/team/{teamId}/player/{playerId}")
    public ClubLegendService.ClubLegendView induct(HttpServletRequest request, @PathVariable long teamId,
                                                   @PathVariable long playerId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> authorizationBody = body == null ? new HashMap<>() : new HashMap<>(body);
        authorizationBody.put("teamId", teamId);
        authorization.authorize(request, authorizationBody, ClubActionAuthorizationService.Action.CLUB_LEGACY);
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return service.induct(teamId, playerId, reason);
    }

    @DeleteMapping("/team/{teamId}/player/{playerId}")
    public ResponseEntity<Void> remove(HttpServletRequest request, @PathVariable long teamId,
                                       @PathVariable long playerId) {
        authorization.authorize(request, Map.of("teamId", teamId),
                ClubActionAuthorizationService.Action.CLUB_LEGACY);
        service.remove(teamId, playerId);
        return ResponseEntity.noContent().build();
    }
}
