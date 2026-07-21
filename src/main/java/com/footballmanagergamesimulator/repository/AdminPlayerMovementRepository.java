package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.AdminPlayerMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminPlayerMovementRepository extends JpaRepository<AdminPlayerMovement, Long> {

    List<AdminPlayerMovement> findAllByOrderByCreatedAtDesc();

    List<AdminPlayerMovement> findAllByStatusAndExecutionSeasonLessThanEqualOrderByIdAsc(
            String status, int executionSeason);

    boolean existsByPlayerIdAndStatus(long playerId, String status);

    List<AdminPlayerMovement> findAllByPlayerIdAndStatus(long playerId, String status);
}
