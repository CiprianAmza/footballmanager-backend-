package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ScoutingFocus;
import com.footballmanagergamesimulator.model.ScoutingFocusResult;
import com.footballmanagergamesimulator.service.ScoutingFocusService;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/scouts/focuses")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ScoutingFocusController {

    private final ScoutingFocusService service;
    private final UserContext users;

    public ScoutingFocusController(ScoutingFocusService service, UserContext users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return service.catalog();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ScoutingFocusService.FocusRequest request,
                                    HttpServletRequest servletRequest) {
        try {
            ScoutingFocus focus = service.create(users.getTeamId(servletRequest), request);
            return ResponseEntity.ok(Map.of("success", true, "focus", focus,
                    "message", focus.getScoutName() + " has started scouting " + focus.getTargetName() + "."));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", exception.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false, defaultValue = "all") String status,
                                          HttpServletRequest request) {
        return service.list(users.getTeamId(request), status);
    }

    @GetMapping("/{focusId}/results")
    public List<ScoutingFocusResult> results(@PathVariable long focusId, HttpServletRequest request) {
        return service.results(users.getTeamId(request), focusId);
    }

    @PostMapping("/{focusId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long focusId, HttpServletRequest request) {
        try {
            ScoutingFocus focus = service.cancel(users.getTeamId(request), focusId);
            return ResponseEntity.ok(Map.of("success", true, "focus", focus,
                    "message", "Recruitment focus cancelled. The scout is available again."));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", exception.getMessage()));
        }
    }
}
