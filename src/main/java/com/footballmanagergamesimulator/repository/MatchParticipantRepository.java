package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchParticipant;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for the canonical squad (starters + bench) of a match. */
public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {
    List<MatchParticipant> findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(MatchPlan matchPlan);
}
