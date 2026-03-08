package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerInbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerInboxRepository extends JpaRepository<ManagerInbox, Long> {

    List<ManagerInbox> findAllByTeamIdOrderByIdDesc(long teamId);

    List<ManagerInbox> findAllByTeamIdAndSeasonNumberOrderByIdDesc(long teamId, int seasonNumber);

    List<ManagerInbox> findAllByTeamIdAndIsReadFalse(long teamId);

    long countByTeamIdAndIsReadFalse(long teamId);

}
