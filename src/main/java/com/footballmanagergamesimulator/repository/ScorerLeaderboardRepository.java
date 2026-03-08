package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface ScorerLeaderboardRepository extends JpaRepository<ScorerLeaderboardEntry, Long> {

    Optional<ScorerLeaderboardEntry> findByPlayerId(long playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ScorerLeaderboardEntry> findAllByPlayerIdIn(List<Long> playerIds);

}
