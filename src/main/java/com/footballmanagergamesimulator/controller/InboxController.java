package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.TeamAccessGuard;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
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
    @Autowired CurrentUserService currentUserService;
    @Autowired PersonProfileRepository profileRepository;

    /**
     * Resolve the effective teamId for inbox queries.
     * If teamId=0 (fired user), fall back to User.lastTeamId so they can still see old messages.
     */
    private Long resolveTeamId(long teamId, HttpServletRequest request) {
        return teamAccessGuard.resolveInboxTeamId(request, teamId);
    }

    @GetMapping("/me")
    public ResponseEntity<List<ManagerInbox>> me(HttpServletRequest request) {
        var user = currentUserService.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).build();
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            return profileRepository.findByUserId(user.getId())
                    .map(profile -> ResponseEntity.ok(managerInboxRepository
                            .findAllByRecipientProfileIdAndAudienceInOrderByIdDesc(profile.getId(), List.of(InboxAudience.CHAIRMAN, InboxAudience.BOTH))))
                    .orElseGet(() -> ResponseEntity.ok(List.of()));
        }
        Long teamId = resolveTeamId(0, request);
        return ResponseEntity.ok(teamId == null ? List.of() : managerInboxRepository
                .findAllByTeamIdAndAudienceInOrderByIdDesc(teamId, List.of(InboxAudience.MANAGER, InboxAudience.BOTH)));
    }

    @GetMapping("/me/unreadCount")
    public ResponseEntity<Long> meUnreadCount(HttpServletRequest request) {
        var user = currentUserService.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).build();
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            return ResponseEntity.ok(profileRepository.findByUserId(user.getId())
                    .map(profile -> managerInboxRepository.countByRecipientProfileIdAndAudienceInAndIsReadFalse(
                            profile.getId(), List.of(InboxAudience.CHAIRMAN, InboxAudience.BOTH))).orElse(0L));
        }
        Long teamId = resolveTeamId(0, request);
        return ResponseEntity.ok(teamId == null ? 0L : managerInboxRepository
                .countByTeamIdAndAudienceInAndIsReadFalse(teamId, List.of(InboxAudience.MANAGER, InboxAudience.BOTH)));
    }

    @PostMapping("/me/{messageId}/read")
    public ResponseEntity<Map<String, Object>> meRead(@PathVariable long messageId, HttpServletRequest request) {
        var user = currentUserService.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).build();
        var message = managerInboxRepository.findById(messageId);
        if (message.isEmpty()) return ResponseEntity.notFound().build();
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            boolean allowed = profileRepository.findByUserId(user.getId()).map(profile ->
                    java.util.Objects.equals(profile.getId(), message.get().getRecipientProfileId())
                            && List.of(InboxAudience.CHAIRMAN, InboxAudience.BOTH).contains(message.get().getAudience())).orElse(false);
            if (!allowed) return ResponseEntity.status(403).body(Map.of("success", false));
        } else if (!teamAccessGuard.canAccessInboxMessage(request, message.get())) {
            return ResponseEntity.status(403).body(Map.of("success", false));
        }
        message.get().setRead(true);
        managerInboxRepository.save(message.get());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/me/markAllRead")
    public ResponseEntity<Map<String, Object>> meMarkAllRead(HttpServletRequest request) {
        var user = currentUserService.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).build();
        List<ManagerInbox> unread;
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            unread = profileRepository.findByUserId(user.getId()).map(profile -> managerInboxRepository
                    .findAllByRecipientProfileIdAndAudienceInOrderByIdDesc(profile.getId(), List.of(InboxAudience.CHAIRMAN, InboxAudience.BOTH)))
                    .orElse(List.of()).stream().filter(m -> !m.isRead()).toList();
        } else {
            Long teamId = resolveTeamId(0, request);
            unread = teamId == null ? List.of() : managerInboxRepository.findAllByTeamIdAndIsReadFalse(teamId);
        }
        unread.forEach(message -> message.setRead(true));
        managerInboxRepository.saveAll(unread);
        return ResponseEntity.ok(Map.of("success", true, "marked", unread.size()));
    }

    @GetMapping("/messages/{teamId}")
    public List<ManagerInbox> getMessages(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdAndAudienceInOrderByIdDesc(effectiveTeamId,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
    }

    @GetMapping("/messages/{teamId}/{season}")
    public List<ManagerInbox> getMessagesBySeason(@PathVariable(name = "teamId") long teamId,
                                                  @PathVariable(name = "season") int season,
                                                  HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdAndSeasonNumberOrderByIdDesc(effectiveTeamId, season).stream()
                .filter(message -> message.getAudience() == InboxAudience.MANAGER || message.getAudience() == InboxAudience.BOTH).toList();
    }

    @GetMapping("/unreadCount/{teamId}")
    public long getUnreadCount(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        Long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId == null || effectiveTeamId <= 0) return 0;
        return managerInboxRepository.countByTeamIdAndAudienceInAndIsReadFalse(effectiveTeamId,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
    }

    @PostMapping("/markRead/{messageId}")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable(name = "messageId") long messageId,
                                                       HttpServletRequest request) {
        Optional<ManagerInbox> opt = managerInboxRepository.findById(messageId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Message not found"));
        }
        ManagerInbox message = opt.get();
        if (!teamAccessGuard.canAccessInboxMessage(request, message)
                || message.getAudience() == InboxAudience.CHAIRMAN) {
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
        List<ManagerInbox> unread = managerInboxRepository.findAllByTeamIdAndAudienceInAndIsReadFalse(effectiveTeamId,
                List.of(InboxAudience.MANAGER, InboxAudience.BOTH));
        unread.forEach(message -> {
            message.setRead(true);
            managerInboxRepository.save(message);
        });
        return ResponseEntity.ok(Map.of("success", true, "marked", unread.size()));
    }

}
