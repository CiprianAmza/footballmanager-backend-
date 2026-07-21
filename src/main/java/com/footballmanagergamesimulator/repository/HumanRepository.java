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

    Page<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNot(
            long typeId, long teamId, Pageable pageable);

    Page<Human> findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(
            long typeId, long teamId, String position, Pageable pageable);

}
