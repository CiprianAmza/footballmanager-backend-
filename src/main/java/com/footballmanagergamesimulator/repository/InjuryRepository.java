package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Injury;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InjuryRepository extends JpaRepository<Injury, Long> {

    List<Injury> findAllByTeamId(long teamId);
    List<Injury> findAllByTeamIdAndDaysRemainingGreaterThan(long teamId, int days);
    List<Injury> findAllByPlayerId(long playerId);
    Optional<Injury> findByPlayerIdAndDaysRemainingGreaterThan(long playerId, int days);
    List<Injury> findAllByDaysRemainingGreaterThan(int days);

    List<Injury> findAllByDaysRemainingGreaterThanAndReturnSeasonLessThanEqual(int days, int returnSeason);

    List<Injury> findAllByPlayerIdInAndDaysRemainingGreaterThan(Collection<Long> playerIds, int days);

    @Query("""
            SELECT i FROM Injury i
             WHERE i.daysRemaining > 0
               AND i.returnSeason > 0
               AND (i.returnSeason < :season
                    OR (i.returnSeason = :season AND i.returnDay <= :day))
            """)
    List<Injury> findAllDueForRecovery(@Param("season") int season, @Param("day") int day);

    // Batch IN-clause — pre-load active injuries for all teams in a round.
    List<Injury> findAllByTeamIdInAndDaysRemainingGreaterThan(Collection<Long> teamIds, int days);

}
