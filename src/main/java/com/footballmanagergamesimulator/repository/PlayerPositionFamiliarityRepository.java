package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerPositionFamiliarity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlayerPositionFamiliarityRepository extends JpaRepository<PlayerPositionFamiliarity, Long> {
    List<PlayerPositionFamiliarity> findAllByPlayerIdIn(Collection<Long> playerIds);
    List<PlayerPositionFamiliarity> findAllByPlayerId(long playerId);
}
