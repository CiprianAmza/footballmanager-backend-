package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamCompetitionRelation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamCompetitionRelationRepository extends JpaRepository<TeamCompetitionRelation, Long> {
}
