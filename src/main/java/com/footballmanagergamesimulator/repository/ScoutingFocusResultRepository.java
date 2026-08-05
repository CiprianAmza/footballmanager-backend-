package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ScoutingFocusResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoutingFocusResultRepository extends JpaRepository<ScoutingFocusResult, Long> {
    List<ScoutingFocusResult> findAllByFocusIdOrderByFitScoreDesc(long focusId);
    void deleteAllByFocusId(long focusId);
}
