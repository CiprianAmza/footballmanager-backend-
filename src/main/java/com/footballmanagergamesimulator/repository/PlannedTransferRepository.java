package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlannedTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlannedTransferRepository extends JpaRepository<PlannedTransfer, Long> {

    List<PlannedTransfer> findAllByStatusAndExecuteOnDayLessThanEqualOrderByExecuteOnDayAscIdAsc(String status, int day);

    List<PlannedTransfer> findAllByStatus(String status);

    boolean existsByPlayerIdAndStatus(long playerId, String status);

    List<PlannedTransfer> findAllByPlayerIdAndStatus(long playerId, String status);
}
