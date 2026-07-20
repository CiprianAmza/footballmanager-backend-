package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Suspension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SuspensionRepository extends JpaRepository<Suspension, Long> {

    List<Suspension> findAllByPlayerIdAndActive(long playerId, boolean active);

    List<Suspension> findAllByTeamIdAndActive(long teamId, boolean active);

    List<Suspension> findAllByTeamIdAndCompetitionIdAndActive(long teamId, long competitionId, boolean active);

    List<Suspension> findAllByCompetitionIdAndTeamIdInAndActive(
            long competitionId, Collection<Long> teamIds, boolean active);

    @Query("select coalesce(sum(s.matchesBanned), 0) from Suspension s "
            + "where s.playerId = :playerId and s.competitionId = :competitionId "
            + "and s.seasonNumber = :season and s.reason = :reason")
    long sumMatchesBannedByPlayerAndCompetitionAndSeasonAndReason(
            @Param("playerId") long playerId,
            @Param("competitionId") long competitionId,
            @Param("season") int seasonNumber,
            @Param("reason") String reason);

    boolean existsBySourceMatchEventIdAndReason(long sourceMatchEventId, String reason);
}
