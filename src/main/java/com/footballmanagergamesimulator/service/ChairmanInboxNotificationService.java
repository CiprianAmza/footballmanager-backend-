package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent, profile-scoped producer for Chairman notifications. */
@Service
public class ChairmanInboxNotificationService {
    private final ManagerInboxRepository repository;

    public ChairmanInboxNotificationService(ManagerInboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean notify(long profileId, long teamId, int season, int round, String category,
                          String title, String content, String deduplicationKey) {
        if (repository.existsByRecipientProfileIdAndDeduplicationKey(profileId, deduplicationKey)) return false;
        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(season);
        message.setRoundNumber(round);
        message.setCategory(category);
        message.setTitle(title);
        message.setContent(content);
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setRecipientProfileId(profileId);
        message.setAudience("CHAIRMAN");
        message.setDeduplicationKey(deduplicationKey);
        repository.save(message);
        return true;
    }
}
