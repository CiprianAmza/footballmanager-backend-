package com.footballmanagergamesimulator.squadplanner;

import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST API for the persistent Squad Planner.
 *
 * All mutations are scoped to the authenticated user's own team
 * (User.teamId == path teamId). Reads of a plan are likewise ownership-checked
 * so one manager cannot inspect another's private plan.
 */
@RestController
@RequestMapping("/squad-planner")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class SquadPlannerController {

    private final SquadPlannerService plannerService;
    private final SquadPlannerAssistant assistant;
    private final CurrentUserService currentUserService;

    public SquadPlannerController(SquadPlannerService plannerService,
                                  SquadPlannerAssistant assistant,
                                  CurrentUserService currentUserService) {
        this.plannerService = plannerService;
        this.assistant = assistant;
        this.currentUserService = currentUserService;
    }

    private User requireOwner(long teamId) {
        User user = currentUserService.getUserOrNull();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        if (!Objects.equals(user.getTeamId(), teamId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your team");
        }
        return user;
    }

    @GetMapping("/{teamId}/{seasonOffset}")
    public List<SquadPlanSlot> getPlan(@PathVariable long teamId, @PathVariable int seasonOffset) {
        requireOwner(teamId);
        return plannerService.getPlan(teamId, seasonOffset);
    }

    /** Bulk save: replaces the whole horizon atomically. */
    @PutMapping("/{teamId}/{seasonOffset}")
    public List<SquadPlanSlot> replacePlan(@PathVariable long teamId, @PathVariable int seasonOffset,
                                           @RequestBody List<SquadPlanSlot> slots) {
        User user = requireOwner(teamId);
        return plannerService.replacePlan(user.getId(), teamId, seasonOffset, slots);
    }

    @PostMapping("/{teamId}/{seasonOffset}/slot")
    public SquadPlanSlot addSlot(@PathVariable long teamId, @PathVariable int seasonOffset,
                                 @RequestBody SquadPlanSlot slot) {
        User user = requireOwner(teamId);
        return plannerService.addSlot(user.getId(), teamId, seasonOffset, slot);
    }

    @PutMapping("/slot/{slotId}")
    public SquadPlanSlot updateSlot(@PathVariable long slotId, @RequestBody SquadPlanSlot slot) {
        User user = requireOwner(slot.getTeamId());
        return plannerService.updateSlot(user.getId(), slotId, slot);
    }

    @DeleteMapping("/slot/{slotId}")
    public Map<String, Object> removeSlot(@PathVariable long slotId, @RequestParam long teamId) {
        User user = requireOwner(teamId);
        plannerService.removeSlot(user.getId(), slotId);
        return Map.of("removed", slotId);
    }

    @GetMapping("/{teamId}/{seasonOffset}/coverage")
    public Map<String, Object> coverage(@PathVariable long teamId, @PathVariable int seasonOffset) {
        requireOwner(teamId);
        return plannerService.analyzeCoverage(teamId, seasonOffset);
    }

    /**
     * Deterministic assistant: fills empty / missing-depth slots and flags
     * recruitment needs. Never overwrites user-locked slots.
     */
    @PostMapping("/{teamId}/{seasonOffset}/assistant")
    public List<SquadPlanSlot> askAssistant(@PathVariable long teamId, @PathVariable int seasonOffset) {
        User user = requireOwner(teamId);
        return assistant.fillPlan(user.getId(), teamId, seasonOffset);
    }
}
