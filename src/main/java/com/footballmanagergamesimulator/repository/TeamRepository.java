package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {

  List<Team> findAllByCompetitionId(long competitionId);

  @Query("SELECT t.name FROM Team t WHERE t.id = :id")
  String findNameById(long id);
}
