package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HumanRepository extends JpaRepository<Human, Long> {

    /** Serializes ownership-changing operations so the same player cannot be sold twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select player from Human player where player.id = :playerId")
    Optional<Human> findByIdForUpdate(@Param("playerId") long playerId);

    List<Human> findAllByTeamId(long teamId);
    List<Human> findAllByTeamIdAndTypeId(long teamId, long typeId);
    List<Human> findAllByTypeId(long typeId);
    List<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNull(long typeId);
    List<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNull(long typeId);
    List<Human> findAllByTypeIdAndRetiredFalseAndTeamId(long typeId, long teamId);
    List<Human> findAllByTeamIdAndTypeIdAndContractEndSeasonLessThanEqual(long teamId, long typeId, int season);
    List<Human> findAllByPreContractTeamId(long teamId);

    // Batch IN-clause lookup — used to pre-load all teams' players in one query
    // at the start of simulateRound to avoid N+1 across the per-match helpers.
    List<Human> findAllByTeamIdInAndTypeId(Collection<Long> teamIds, long typeId);
    List<Human> findAllByTeamIdInAndTypeIdAndRetiredFalse(Collection<Long> teamIds, long typeId);

    /**
     * Lightweight aggregate used by the daily club valuation pass. Loading every
     * player entity just to sum transfer values made Fast Forward repeatedly hydrate
     * the whole football world.
     */
    @Query("""
            select player.teamId as teamId,
                   sum(case when player.retired = false and player.transferValue > 0
                            then player.transferValue else 0 end) as totalValue
              from Human player
             where player.teamId in :teamIds and player.typeId = :typeId
             group by player.teamId
            """)
    List<TeamValueTotal> sumActiveTransferValueByTeamIdsAndTypeId(
            @Param("teamIds") Collection<Long> teamIds, @Param("typeId") long typeId);

    /** One bounded query for all coaching roles belonging to the requested teams. */
    List<Human> findAllByTeamIdInAndTypeIdIn(Collection<Long> teamIds, Collection<Long> typeIds);

    /** Batch lookup for a club's complete payroll (players, manager and staff). */
    List<Human> findAllByTeamIdIn(Collection<Long> teamIds);

    Page<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNot(
            long typeId, long teamId, Pageable pageable);

    Page<Human> findAllByTypeIdAndRetiredFalseAndWillNeverLeaveFalseAndTeamIdIsNotNullAndTeamIdNot(
            long typeId, long teamId, Pageable pageable);

    Page<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(
            long typeId, long teamId, String position, Pageable pageable);

    Page<Human> findAllByTypeIdAndRetiredFalseAndWillNeverLeaveFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(
            long typeId, long teamId, String position, Pageable pageable);

}
