package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerFootProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerFootProfileRepository extends JpaRepository<PlayerFootProfile, Long> {
    List<PlayerFootProfile> findAllByPlayerIdIn(Collection<Long> playerIds);
    Optional<PlayerFootProfile> findByPlayerId(long playerId);
}
