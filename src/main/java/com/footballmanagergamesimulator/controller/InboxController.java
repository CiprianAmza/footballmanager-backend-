package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/inbox")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class InboxController {

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @Autowired
    UserContext userContext;

    /**
     * Resolve the effective teamId for inbox queries.
     * If teamId=0 (fired user), fall back to User.lastTeamId so they can still see old messages.
     */
    private long resolveTeamId(long teamId, HttpServletRequest request) {
        if (teamId > 0) return teamId;
        User user = userContext.getUserOrNull(request);
        if (user != null && user.getLastTeamId() != null && user.getLastTeamId() > 0) {
            return user.getLastTeamId();
        }
        return teamId;
    }

    @GetMapping("/messages/{teamId}")
    public List<ManagerInbox> getMessages(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdOrderByIdDesc(effectiveTeamId);
    }

    @GetMapping("/messages/{teamId}/{season}")
    public List<ManagerInbox> getMessagesBySeason(@PathVariable(name = "teamId") long teamId,
                                                  @PathVariable(name = "season") int season,
                                                  HttpServletRequest request) {
        long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId <= 0) return Collections.emptyList();
        return managerInboxRepository.findAllByTeamIdAndSeasonNumberOrderByIdDesc(effectiveTeamId, season);
    }

    @GetMapping("/unreadCount/{teamId}")
    public long getUnreadCount(@PathVariable(name = "teamId") long teamId, HttpServletRequest request) {
        long effectiveTeamId = resolveTeamId(teamId, request);
        if (effectiveTeamId <= 0) return 0;
        return managerInboxRepository.countByTeamIdAndIsReadFalse(effectiveTeamId);
    }

    @PostMapping("/markRead/{messageId}")
    public void markRead(@PathVariable(name = "messageId") long messageId) {
        managerInboxRepository.findById(messageId).ifPresent(message -> {
            message.setRead(true);
            managerInboxRepository.save(message);
        });
    }

    @PostMapping("/markAllRead/{teamId}")
    public void markAllRead(@PathVariable(name = "teamId") long teamId) {
        List<ManagerInbox> unread = managerInboxRepository.findAllByTeamIdAndIsReadFalse(teamId);
        unread.forEach(message -> {
            message.setRead(true);
            managerInboxRepository.save(message);
        });
    }

}
