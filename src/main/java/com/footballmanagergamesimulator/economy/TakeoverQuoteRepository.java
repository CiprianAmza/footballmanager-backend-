package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TakeoverQuoteRepository extends JpaRepository<TakeoverQuote, Long> {
    Optional<TakeoverQuote> findByBuyerAccountIdAndIdempotencyKey(long accountId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quote from TakeoverQuote quote where quote.quoteKey = :quoteKey")
    Optional<TakeoverQuote> findByQuoteKeyForUpdate(@Param("quoteKey") String quoteKey);
}
