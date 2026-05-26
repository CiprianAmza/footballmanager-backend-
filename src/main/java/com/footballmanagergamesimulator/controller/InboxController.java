package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.TeamAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/inbox")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class InboxController {

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @Autowired
    TeamAccessGuard teamAccessGuard;

    /**
     * Resolve the effective teamId for inbox queries.
     * If teamId=0 (fired user), fall back to User.lastTeamId so they can still see old messages.
     */
    private Long resolveTeamId(long teamId, HttpServletRequest request) {
        return teamAccessGuard.resolveInboxTeamId(request, teamId);
    }

    @GetMapping("/messages/{teamId}")
    public List<ManagerInbox> getMessages(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdOrderByIdDesc(effectiveTeamId);
    }

    @GetMapping("/messages/{teamId}/{season}")
    public List<ManagerInbox> getMessagesBySeason(@PathVariable(name = "teamId") long teamId,
                                                  @PathVariable(name = "season") int season,
                                                  HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdAndSeasonNumberOrderByIdDesc(effectiveTeamId, season);
    }

    @GetMapping("/unreadCount/{teamId}")
    public long getUnreadCount(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return 0;
        return managerInboxRepository.countByTeamIdAndIsReadFalse(effectiveTeamId);
    }

    @PostMapping("/markRead/{messageId}")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable(name = "messageId") long messageId,
                                                       HttpServletRequest request) {
        Optional<ManagerInbox> opt = managerInboxRepository.findById(messageId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Message not found"));
        }
        ManagerInbox message = opt.get();
        if (!teamAccessGuard.canAccessInboxMessage(request, message)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not allowed"));
        }
        message.setRead(true);
        managerInboxRepository.save(message);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/markAllRead/{teamId}")
    public ResponseEntity<Map<String, Object>> markAllRead(@PathVariable(name = "teamId") long teamId,
                                                          HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not allowed"));
        }
        List<ManagerInbox> unread = managerInboxRepository.findAllByTeamIdAndIsReadFalse(effectiveTeamId);
        unread.forEach(message -> {
            message.setRead(true);
            managerInboxRepository.save(message);
        });
        return ResponseEntity.ok(Map.of("success", true, "marked", unread.size()));
    }

}
