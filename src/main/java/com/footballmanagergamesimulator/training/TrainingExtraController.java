package com.footballmanagergamesimulator.training;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.service.StaffService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * REST API for the FORGE training extensions: training units (with coach
 * workload/quality explainability) and mentoring groups (with per-mentee
 * effect estimates). All mutations are scoped to the authenticated user's team.
 */
@RestController
@RequestMapping("/training")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TrainingExtraController {

    private final TrainingUnitService unitService;
    private final MentoringService mentoringService;
    private final StaffService staffService;
    private final CurrentUserService currentUserService;

    public TrainingExtraController(TrainingUnitService unitService,
                                   MentoringService mentoringService,
                                   StaffService staffService,
                                   CurrentUserService currentUserService) {
        this.unitService = unitService;
        this.mentoringService = mentoringService;
        this.staffService = staffService;
        this.currentUserService = currentUserService;
    }

    private void requireOwner(long teamId) {
        User user = currentUserService.getUserOrNull();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        if (!Objects.equals(user.getTeamId(), teamId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your team");
        }
    }

    // ---- Units ----

    @GetMapping("/units/{teamId}")
    public Map<String, Object> units(@PathVariable long teamId) {
        requireOwner(teamId);
        return unitService.getUnitsOverview(teamId);
    }

    @PostMapping("/units/{teamId}/player")
    public PlayerUnitAssignment assignPlayer(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        requireOwner(teamId);
        long playerId = asLong(body.get("playerId"));
        String unit = String.valueOf(body.get("unit"));
        return unitService.assignPlayer(teamId, playerId, unit);
    }

    @PostMapping("/units/{teamId}/coach")
    public UnitCoachAssignment assignCoach(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        requireOwner(teamId);
        long coachId = asLong(body.get("coachId"));
        String unit = String.valueOf(body.get("unit"));
        return unitService.assignCoach(teamId, coachId, unit);
    }

    @DeleteMapping("/units/{teamId}/coach/{assignmentId}")
    public Map<String, Object> removeCoach(@PathVariable long teamId, @PathVariable long assignmentId) {
        requireOwner(teamId);
        unitService.removeCoach(teamId, assignmentId);
        return Map.of("removed", assignmentId);
    }

    /** Team coaching staff available for unit assignment. */
    @GetMapping("/coaches/{teamId}")
    public List<Map<String, Object>> coaches(@PathVariable long teamId) {
        requireOwner(teamId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Human c : staffService.getTeamStaff(teamId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coachId", c.getId());
            m.put("name", c.getName());
            m.put("coachingAttacking", c.getCoachingAttacking());
            m.put("coachingDefending", c.getCoachingDefending());
            m.put("coachingGK", c.getCoachingGK());
            m.put("coachingFitness", c.getCoachingFitness());
            out.add(m);
        }
        return out;
    }

    // ---- Mentoring ----

    @GetMapping("/mentoring/{teamId}")
    public List<Map<String, Object>> mentoringGroups(@PathVariable long teamId) {
        requireOwner(teamId);
        return mentoringService.listGroups(teamId);
    }

    @PostMapping("/mentoring/{teamId}")
    public Map<String, Object> saveMentoringGroup(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        requireOwner(teamId);
        Long groupId = body.get("groupId") == null ? null : asLong(body.get("groupId"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        List<Long> playerIds = new ArrayList<>();
        Object raw = body.get("playerIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) playerIds.add(asLong(o));
        }
        return mentoringService.saveGroup(teamId, groupId, name, playerIds);
    }

    @DeleteMapping("/mentoring/{teamId}/{groupId}")
    public Map<String, Object> deleteMentoringGroup(@PathVariable long teamId, @PathVariable long groupId) {
        requireOwner(teamId);
        mentoringService.deleteGroup(teamId, groupId);
        return Map.of("removed", groupId);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}
