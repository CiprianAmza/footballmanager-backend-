package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Analyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalystRepository extends JpaRepository<Analyst, Long> {

    List<Analyst> findAllByCompetitionId(long competitionId);

    List<Analyst> findAllByCompetitionIdAndRoleAndBeat(long competitionId, Analyst.Role role, Analyst.Beat beat);

    boolean existsByRole(Analyst.Role role);

    /** Journalists created before the style parameters existed. */
    List<Analyst> findAllByVoiceIsNull();

    boolean existsByVoiceIsNull();
}
