package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ScoutAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScoutAssignmentRepository extends JpaRepository<ScoutAssignment, Long> {

    List<ScoutAssignment> findAllByTeamIdAndStatus(long teamId, String status);

    List<ScoutAssignment> findAllByTeamIdAndSeasonAndStatus(long teamId, int season, String status);

    List<ScoutAssignment> findAllByScoutIdAndStatus(long scoutId, String status);

    List<ScoutAssignment> findAllBySeasonAndStatusAndEndDayLessThanEqual(int season, String status, int day);

    List<ScoutAssignment> findAllByTeamId(long teamId);

    List<ScoutAssignment> findAllByTeamIdAndPlayerIdAndStatus(long teamId, long playerId, String status);
}
