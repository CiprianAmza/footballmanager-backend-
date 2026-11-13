package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.press.FirstPressConferenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** The first press conference at a new club (design DESIGN_START_CARIERA §6.4). */
@RestController
@RequestMapping("/press-conference/first")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class FirstPressConferenceController {

    private final FirstPressConferenceService conferences;
    private final ClubActionAuthorizationService authorization;

    public FirstPressConferenceController(FirstPressConferenceService conferences,
                                          ClubActionAuthorizationService authorization) {
        this.conferences = conferences;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public FirstPressConferenceService.Conference conference(@PathVariable long teamId) {
        return conferences.conference(teamId);
    }

    /** Body: {"teamId": 1, "articleAnswer": "PROVE_WRONG", "targets": {"5": "TOP_3"}}. */
    @PostMapping
    public ResponseEntity<?> hold(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.MANAGER_DUTY).teamId();
        Map<Long, String> targets = new HashMap<>();
        if (body.get("targets") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> targets.put(Long.parseLong(String.valueOf(k)), String.valueOf(v)));
        }
        try {
            Object answer = body.get("articleAnswer");
            Object puppet = body.get("puppetAnswer");
            return ResponseEntity.ok(conferences.hold(teamId,
                    answer == null ? null : FirstPressConferenceService.ArticleAnswer.valueOf(String.valueOf(answer)), targets,
                    puppet == null ? null : FirstPressConferenceService.PuppetAnswer.valueOf(String.valueOf(puppet))));
        } catch (IllegalStateException | IllegalArgumentException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }

    @PostMapping("/refuse")
    public ResponseEntity<?> refuse(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.MANAGER_DUTY).teamId();
        try {
            return ResponseEntity.ok(conferences.refuse(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }
}
