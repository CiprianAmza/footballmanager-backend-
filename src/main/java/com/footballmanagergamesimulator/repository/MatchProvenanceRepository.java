package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchProvenance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchProvenanceRepository extends JpaRepository<MatchProvenance, Long> {

    /** Idempotency lookup: the {@code fixture_key} column is uniquely constrained. */
    Optional<MatchProvenance> findByFixtureKey(String fixtureKey);
}
