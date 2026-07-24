package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerRoleFamiliarity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlayerRoleFamiliarityRepository extends JpaRepository<PlayerRoleFamiliarity, Long> {
    List<PlayerRoleFamiliarity> findAllByPlayerIdIn(Collection<Long> playerIds);
    List<PlayerRoleFamiliarity> findAllByPlayerId(long playerId);
}
