package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Competition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {
}
