package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchParticipant;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for the canonical squad (starters + bench) of a match. */
public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {
    List<MatchParticipant> findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(MatchPlan matchPlan);

    /** Detail retention: drops the kickoff participants of these (finished) fixtures. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from MatchParticipant p where p.matchPlan.id in "
            + "(select m.id from MatchPlan m where m.fixtureKey in :keys)")
    int deleteForFixtures(@org.springframework.data.repository.query.Param("keys") java.util.Collection<String> keys);
}
