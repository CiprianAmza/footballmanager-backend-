package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchAppearance;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for the canonical per-player appearance timeline of a match. */
public interface MatchAppearanceRepository extends JpaRepository<MatchAppearance, Long> {

    List<MatchAppearance> findByMatchPlan(MatchPlan matchPlan);

    boolean existsByMatchPlan(MatchPlan matchPlan);
}
