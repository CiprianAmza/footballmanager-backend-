package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.service.StaffService;
import com.footballmanagergamesimulator.service.StaffIntelligenceService;
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
    private StaffIntelligenceService staffIntelligenceService;

    @Autowired
    private UserContext userContext;

    @Autowired
    private com.footballmanagergamesimulator.service.board.StaffLoyaltyService staffLoyalty;

    @GetMapping("/overview/{teamId}")
    public Map<String, Object> getStaffOverview(@PathVariable long teamId) {
        return staffService.getStaffOverview(teamId);
    }

    @GetMapping("/available")
    public List<Human> getAvailableCoaches() {
        return staffService.getFreeAgentCoaches();
    }

    @GetMapping("/intelligence/{teamId}")
    public StaffIntelligenceService.StaffIntelligence getStaffIntelligence(@PathVariable long teamId) {
        return staffIntelligenceService.intelligence(teamId);
    }

    @GetMapping("/market/{teamId}")
    public List<StaffIntelligenceService.StaffCandidate> getStaffMarket(@PathVariable long teamId) {
        return staffIntelligenceService.market(teamId);
    }

    @PostMapping("/offer")
    public ResponseEntity<StaffIntelligenceService.OfferResult> makeStaffOffer(
            HttpServletRequest request, @RequestBody StaffIntelligenceService.StaffOffer offer) {
        long teamId = userContext.getTeamId(request);
        StaffIntelligenceService.OfferResult result = staffIntelligenceService.makeOffer(teamId, offer);
        if (result.success() && "COACH".equalsIgnoreCase(offer.kind())) staffLoyalty.hired(offer.staffId(), teamId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hire")
    public ResponseEntity<?> hireCoach(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long coachId = ((Number) body.get("coachId")).longValue();
        long teamId = userContext.getTeamId(request);
        Map<String, Object> result = staffService.hireCoach(coachId, teamId);
        if (Boolean.TRUE.equals(result.get("success"))) staffLoyalty.hired(coachId, teamId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fire/{coachId}")
    public ResponseEntity<?> fireCoach(HttpServletRequest request, @PathVariable long coachId) {
        long teamId = userContext.getTeamId(request);
        return ResponseEntity.ok(staffLoyalty.fire(teamId, coachId));
    }

    /** The director insisted on keeping him: take it to the chairman (design DESIGN_START_CARIERA §5.2). */
    @PostMapping("/fire/{coachId}/appeal")
    public ResponseEntity<?> appealToChairman(HttpServletRequest request, @PathVariable long coachId) {
        return ResponseEntity.ok(staffLoyalty.appeal(userContext.getTeamId(request), coachId));
    }

    /** Praise a member of staff in public (once a season counts). */
    @PostMapping("/praise/{coachId}")
    public ResponseEntity<?> praise(HttpServletRequest request, @PathVariable long coachId) {
        return ResponseEntity.ok(staffLoyalty.praise(userContext.getTeamId(request), coachId));
    }

    /** The assistant's read of each colleague: the only hint of where their loyalty lies. */
    @GetMapping("/assistant-reads/{teamId}")
    public Map<Long, String> assistantReads(@PathVariable long teamId) {
        return staffLoyalty.assistantReads(teamId);
    }
}
