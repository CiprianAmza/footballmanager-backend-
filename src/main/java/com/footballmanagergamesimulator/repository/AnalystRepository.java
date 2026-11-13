package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Analyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalystRepository extends JpaRepository<Analyst, Long> {

    List<Analyst> findAllByCompetitionId(long competitionId);
}
