package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchSubstitution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for the canonical, sub-index-ordered substitutions of a match. */
public interface MatchSubstitutionRepository extends JpaRepository<MatchSubstitution, Long> {
    List<MatchSubstitution> findByMatchPlanOrderByTeamIdAscSubIndexAsc(MatchPlan matchPlan);
}
