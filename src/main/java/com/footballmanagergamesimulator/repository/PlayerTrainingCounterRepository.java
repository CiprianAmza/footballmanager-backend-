package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerTrainingCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PlayerTrainingCounterRepository extends JpaRepository<PlayerTrainingCounter, Long> {

    List<PlayerTrainingCounter> findAllByTeamIdAndPlayerIdIn(long teamId, Collection<Long> playerIds);

    List<PlayerTrainingCounter> findAllByTeamId(long teamId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PlayerTrainingCounter c set c.sessions = 0 where c.teamId = :teamId")
    int resetCounts(@Param("teamId") long teamId);
}
