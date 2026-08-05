package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.InboxAudience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerInboxRepository extends JpaRepository<ManagerInbox, Long> {

    List<ManagerInbox> findAllByTeamIdOrderByIdDesc(long teamId);
    List<ManagerInbox> findAllByTeamIdAndAudienceInOrderByIdDesc(long teamId, List<InboxAudience> audiences);

    List<ManagerInbox> findAllByTeamIdAndSeasonNumberOrderByIdDesc(long teamId, int seasonNumber);

    List<ManagerInbox> findAllByTeamIdAndCategoryOrderByIdDesc(long teamId, String category);

    List<ManagerInbox> findAllByTeamIdAndIsReadFalse(long teamId);
    List<ManagerInbox> findAllByTeamIdAndAudienceInAndIsReadFalse(long teamId, List<InboxAudience> audiences);

    long countByTeamIdAndIsReadFalse(long teamId);
    long countByTeamIdAndAudienceInAndIsReadFalse(long teamId, List<InboxAudience> audiences);

    List<ManagerInbox> findAllByRecipientProfileIdAndAudienceInOrderByIdDesc(long profileId, List<InboxAudience> audiences);

    long countByRecipientProfileIdAndAudienceInAndIsReadFalse(long profileId, List<InboxAudience> audiences);

    boolean existsByRecipientProfileIdAndDeduplicationKey(Long recipientProfileId, String deduplicationKey);

    boolean existsByTeamIdAndDeduplicationKey(long teamId, String deduplicationKey);

}
