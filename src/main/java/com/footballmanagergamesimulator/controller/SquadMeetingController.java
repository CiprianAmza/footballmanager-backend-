package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.squad.SquadMeetingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** The meeting with the squad: competition objectives (design DESIGN_START_CARIERA §6.4). */
@RestController
@RequestMapping("/squad-meeting")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class SquadMeetingController {

    private final SquadMeetingService meetings;
    private final ClubActionAuthorizationService authorization;

    public SquadMeetingController(SquadMeetingService meetings, ClubActionAuthorizationService authorization) {
        this.meetings = meetings;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public SquadMeetingService.Agenda agenda(@PathVariable long teamId) {
        return meetings.agenda(teamId);
    }

    /** Refuse the meeting this season: the players' trust and morale drop. */
    @PostMapping("/refuse")
    public ResponseEntity<?> refuse(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(meetings.refuse(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }

    /** Body: {"teamId": 1, "targets": {"5": "TOP_3", "9": "SEMI_FINAL"}}. Once per season. */
    @PostMapping
    public ResponseEntity<?> hold(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        Map<Long, String> targets = new HashMap<>();
        if (body.get("targets") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> targets.put(Long.parseLong(String.valueOf(k)), String.valueOf(v)));
        }
        Map<Long, String> bonuses = new HashMap<>();
        if (body.get("bonuses") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> bonuses.put(Long.parseLong(String.valueOf(k)), String.valueOf(v)));
        }
        try {
            return ResponseEntity.ok(meetings.hold(teamId, targets, bonuses));
        } catch (IllegalStateException | IllegalArgumentException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }
}
