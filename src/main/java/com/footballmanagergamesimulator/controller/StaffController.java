package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.service.StaffService;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/staff")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class StaffController {

    @Autowired
    private StaffService staffService;

    @Autowired
    private UserContext userContext;

    @GetMapping("/overview/{teamId}")
    public Map<String, Object> getStaffOverview(@PathVariable long teamId) {
        return staffService.getStaffOverview(teamId);
    }

    @GetMapping("/available")
    public List<Human> getAvailableCoaches() {
        return staffService.getFreeAgentCoaches();
    }

    @PostMapping("/hire")
    public ResponseEntity<?> hireCoach(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long coachId = ((Number) body.get("coachId")).longValue();
        long teamId = userContext.getTeamId(request);
        return ResponseEntity.ok(staffService.hireCoach(coachId, teamId));
    }

    @PostMapping("/fire/{coachId}")
    public ResponseEntity<?> fireCoach(HttpServletRequest request, @PathVariable long coachId) {
        long teamId = userContext.getTeamId(request);
        return ResponseEntity.ok(staffService.fireCoach(coachId, teamId));
    }
}
