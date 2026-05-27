package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Competition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    @Query("SELECT c.typeId FROM Competition c WHERE c.id = :id")
    Long findTypeIdById(@Param("id") long id);

    @Query("SELECT c.name FROM Competition c WHERE c.id = :id")
    String findNameById(@Param("id") long id);

    @Query("SELECT c.id FROM Competition c WHERE c.typeId = :typeId")
    Set<Long> findIdsByTypeId(@Param("typeId") long typeId);
}

