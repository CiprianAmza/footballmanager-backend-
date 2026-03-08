package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/inbox")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class InboxController {

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @GetMapping("/messages/{teamId}")
    public List<ManagerInbox> getMessages(@PathVariable(name = "teamId") long teamId) {
        return managerInboxRepository.findAllByTeamIdOrderByIdDesc(teamId);
    }

    @GetMapping("/messages/{teamId}/{season}")
    public List<ManagerInbox> getMessagesBySeason(@PathVariable(name = "teamId") long teamId,
                                                  @PathVariable(name = "season") int season) {
        return managerInboxRepository.findAllByTeamIdAndSeasonNumberOrderByIdDesc(teamId, season);
    }

    @GetMapping("/unreadCount/{teamId}")
    public long getUnreadCount(@PathVariable(name = "teamId") long teamId) {
        return managerInboxRepository.countByTeamIdAndIsReadFalse(teamId);
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
