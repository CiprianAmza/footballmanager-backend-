package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressConferenceSession;
import com.footballmanagergamesimulator.model.PressConferenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PressConferenceSessionRepository extends JpaRepository<PressConferenceSession, Long> {

    List<PressConferenceSession> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    /**
     * Idempotency lookup: a session is unique per (team, type, fixture, season).
     * Used so retrying Start returns the existing session instead of creating a
     * duplicate.
     */
    Optional<PressConferenceSession> findByTeamIdAndTypeAndFixtureKeyAndSeasonNumber(
            long teamId, PressConferenceType type, String fixtureKey, int seasonNumber);
}
