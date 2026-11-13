package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.board.StaffMeetingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The first staff meeting of a season (design DESIGN_START_CARIERA §6.4). */
@RestController
@RequestMapping("/staff-meeting")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class StaffMeetingController {

    private final StaffMeetingService meetings;
    private final ClubActionAuthorizationService authorization;

    public StaffMeetingController(StaffMeetingService meetings, ClubActionAuthorizationService authorization) {
        this.meetings = meetings;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public StaffMeetingService.StaffMeeting meeting(@PathVariable long teamId) {
        return meetings.meeting(teamId);
    }

    /** Body: {"teamId": 1, "emphasis": "TACTICAL", "philosophy": "YOUTH"}. */
    @PostMapping
    public ResponseEntity<?> hold(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(meetings.hold(teamId,
                    StaffMeetingService.Emphasis.valueOf(String.valueOf(body.get("emphasis"))),
                    StaffMeetingService.Philosophy.valueOf(String.valueOf(body.get("philosophy")))));
        } catch (IllegalStateException | IllegalArgumentException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }

    @PostMapping("/refuse")
    public ResponseEntity<?> refuse(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(meetings.refuse(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }
}
