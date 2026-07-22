package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Team;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from Team t where t.id = :teamId")
  java.util.Optional<Team> findByIdForUpdate(@Param("teamId") long teamId);

  List<Team> findAllByCompetitionId(long competitionId);

  @Query("SELECT t.name FROM Team t WHERE t.id = :id")
  String findNameById(long id);
}
