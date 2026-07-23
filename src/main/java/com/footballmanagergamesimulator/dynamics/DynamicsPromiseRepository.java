package com.footballmanagergamesimulator.dynamics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DynamicsPromiseRepository extends JpaRepository<DynamicsPromise, Long> {

    List<DynamicsPromise> findByTeamId(long teamId);

    List<DynamicsPromise> findByTeamIdAndStatus(long teamId, PromiseStatus status);

    List<DynamicsPromise> findByPlayerId(long playerId);

    List<DynamicsPromise> findByPlayerIdAndStatus(long playerId, PromiseStatus status);
}
