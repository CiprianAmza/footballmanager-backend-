package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.ClubActionAuthorizationService;
import com.footballmanagergamesimulator.service.board.BoardMeetingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The first-day meeting with the chairman and the general director (design DESIGN_START_CARIERA §6.2). */
@RestController
@RequestMapping("/board-meeting")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class BoardMeetingController {

    private final BoardMeetingService meetings;
    private final ClubActionAuthorizationService authorization;

    public BoardMeetingController(BoardMeetingService meetings, ClubActionAuthorizationService authorization) {
        this.meetings = meetings;
        this.authorization = authorization;
    }

    @GetMapping("/{teamId}")
    public BoardMeetingService.Meeting meeting(@PathVariable long teamId) {
        return meetings.meeting(teamId);
    }

    /** Body: {"teamId": 1}. Ends a meeting in which nobody asked anything. */
    @PostMapping("/close")
    public ResponseEntity<?> close(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(meetings.close(teamId));
        } catch (IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }

    /** Body: {"teamId": 1, "answer": "WITH_YOU"}. A private answer to the ambitious director. */
    @PostMapping("/answer")
    public ResponseEntity<?> answer(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = authorization.authorize(request, body, ClubActionAuthorizationService.Action.CONTRACT).teamId();
        try {
            return ResponseEntity.ok(meetings.answer(teamId,
                    BoardMeetingService.Answer.valueOf(String.valueOf(body.get("answer")))));
        } catch (IllegalStateException | IllegalArgumentException refused) {
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage()));
        }
    }
}
