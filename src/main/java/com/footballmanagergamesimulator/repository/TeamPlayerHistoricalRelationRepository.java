package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamPlayerHistoricalRelationRepository extends JpaRepository<TeamPlayerHistoricalRelation, Long> {

    List<TeamPlayerHistoricalRelation> findByPlayerId(long playerId);
    List<TeamPlayerHistoricalRelation> findByPlayerIdAndSeasonNumber(long playerId, long seasonNumber);
}
