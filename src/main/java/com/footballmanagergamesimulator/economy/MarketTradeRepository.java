package com.footballmanagergamesimulator.economy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketTradeRepository extends JpaRepository<MarketTrade, Long> {
    Optional<MarketTrade> findByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey);
    Page<MarketTrade> findAllByAccountId(long accountId, Pageable pageable);
}
