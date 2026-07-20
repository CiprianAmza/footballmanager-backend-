package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Competition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Set;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    /** Serializes phase draws for one competition while matchdays run in parallel. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Competition c WHERE c.id = :id")
    Optional<Competition> findByIdForFixturePreparation(@Param("id") long id);

    @Query("SELECT c.typeId FROM Competition c WHERE c.id = :id")
    Long findTypeIdById(@Param("id") long id);

    @Query("SELECT c.name FROM Competition c WHERE c.id = :id")
    String findNameById(@Param("id") long id);

    @Query("SELECT c.id FROM Competition c WHERE c.typeId = :typeId")
    Set<Long> findIdsByTypeId(@Param("typeId") long typeId);
}
