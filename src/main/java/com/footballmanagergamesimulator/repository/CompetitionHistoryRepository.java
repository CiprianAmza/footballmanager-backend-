package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionHistoryRepository extends JpaRepository<CompetitionHistory, Long> {
}
