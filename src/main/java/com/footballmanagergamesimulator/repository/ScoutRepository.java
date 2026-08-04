package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Scout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;

@Repository
public interface ScoutRepository extends JpaRepository<Scout, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Scout s where s.id = :scoutId")
    java.util.Optional<Scout> findByIdForUpdate(@Param("scoutId") long scoutId);

    List<Scout> findAllByTeamId(long teamId);

    List<Scout> findAllByTeamIdIsNull();

    List<Scout> findAllByTeamIdAndContractEndSeasonLessThanEqual(long teamId, int season);
}
