package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.conduct.ConductService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The club's code of conduct (design DESIGN_START_CARIERA §6.4). */
@RestController
@RequestMapping("/conduct")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ConductController {

    private final ConductService conductService;
    private final ClubActionAuthorizationService authorization;

    public ConductController(ConductService conductService, ClubActionAuthorizationService authorization) {
        this.conductService = conductService;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public Map<String, Object> code(@PathVariable long teamId) {
        return Map.of("code", conductService.code(teamId), "allowedWeeks", ConductService.ALLOWED_WEEKS);
    }

    /** Body: {"fines": {"RED_CARD": 2, ...}}. The squad reacts to fines raised above the standard. */
    @PutMapping
    public ResponseEntity<?> update(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        Map<String, Double> fines = new HashMap<>();
        Object raw = body.get("fines");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> fines.put(String.valueOf(k), v == null ? null : ((Number) v).doubleValue()));
        }
        try {
            ConductService.Update update = conductService.update(teamId, fines);
            return ResponseEntity.ok(Map.of("code", update.code(), "moraleDrop", update.moraleDrop(),
                    "allowedWeeks", ConductService.ALLOWED_WEEKS));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }
}
