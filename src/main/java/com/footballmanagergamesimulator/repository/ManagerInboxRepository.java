package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerInbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerInboxRepository extends JpaRepository<ManagerInbox, Long> {

    List<ManagerInbox> findAllByTeamIdOrderByIdDesc(long teamId);

    List<ManagerInbox> findAllByTeamIdAndSeasonNumberOrderByIdDesc(long teamId, int seasonNumber);

    List<ManagerInbox> findAllByTeamIdAndIsReadFalse(long teamId);

    long countByTeamIdAndIsReadFalse(long teamId);

    List<ManagerInbox> findAllByRecipientProfileIdAndAudienceInOrderByIdDesc(long profileId, List<String> audiences);

    long countByRecipientProfileIdAndAudienceInAndIsReadFalse(long profileId, List<String> audiences);

    boolean existsByRecipientProfileIdAndDeduplicationKey(Long recipientProfileId, String deduplicationKey);

}
