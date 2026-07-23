package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClubCashTransferRepository extends JpaRepository<ClubCashTransfer, Long> {
    Optional<ClubCashTransfer> findByAccountIdAndIdempotencyKey(long accountId, String key);
    List<ClubCashTransfer> findAllByTeamIdOrderByIdDesc(long teamId);
}
