package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ConductFine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConductFineRepository extends JpaRepository<ConductFine, Long> {

    boolean existsByMatchEventId(long matchEventId);

    List<ConductFine> findAllByTeamIdAndSeason(long teamId, int season);
}
