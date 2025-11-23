package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScorerLeaderboardRepository extends JpaRepository<ScorerLeaderboardEntry, Long> {

    Optional<ScorerLeaderboardEntry> findByPlayerId(long playerId);

}