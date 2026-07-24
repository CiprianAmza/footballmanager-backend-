package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** Idempotent, profile-scoped producer for Chairman notifications. */
@Service
public class ChairmanInboxNotificationService {
    private final ManagerInboxRepository repository;
    private final PersonProfileRepository profileRepository;

    public ChairmanInboxNotificationService(ManagerInboxRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ChairmanInboxNotificationService(ManagerInboxRepository repository, PersonProfileRepository profileRepository) {
        this.repository = repository;
        this.profileRepository = profileRepository;
    }

    @Transactional
    public boolean notify(long profileId, long teamId, int season, int round, String category,
                          String title, String content, String deduplicationKey) {
        if (profileRepository != null) {
            profileRepository.findByIdForUpdate(profileId)
                    .orElseThrow(() -> new IllegalStateException("Chairman profile not found: " + profileId));
        }
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
        message.setAudience(InboxAudience.CHAIRMAN);
        message.setDeduplicationKey(deduplicationKey);
        repository.save(message);
        return true;
    }
}
