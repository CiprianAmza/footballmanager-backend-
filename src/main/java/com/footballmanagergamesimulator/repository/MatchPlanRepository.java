package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Persistence for canonical {@link MatchPlan}s, looked up by the real fixture key. */
public interface MatchPlanRepository extends JpaRepository<MatchPlan, Long> {

    Optional<MatchPlan> findByFixtureKey(String fixtureKey);

    boolean existsByFixtureKey(String fixtureKey);
}
