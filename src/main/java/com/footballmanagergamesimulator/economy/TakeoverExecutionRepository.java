package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TakeoverExecutionRepository extends JpaRepository<TakeoverExecution, Long> {
    Optional<TakeoverExecution> findByBuyerAccountIdAndIdempotencyKey(long accountId, String key);
}
