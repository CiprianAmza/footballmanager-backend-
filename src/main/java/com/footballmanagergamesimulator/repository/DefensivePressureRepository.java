package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.DefensivePressure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DefensivePressureRepository extends JpaRepository<DefensivePressure, Long> {
    List<DefensivePressure> findAllByMatchStatsIdOrderByTeamIdAsc(long matchStatsId);
    void deleteAllByMatchStatsId(long matchStatsId);
}
