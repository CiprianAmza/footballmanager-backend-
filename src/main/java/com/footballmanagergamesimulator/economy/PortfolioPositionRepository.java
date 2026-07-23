package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, Long> {
    List<PortfolioPosition> findAllByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(long accountId, long minimum);
    List<PortfolioPosition> findAllByQuantityGreaterThan(long minimum);
    Optional<PortfolioPosition> findByAccountIdAndInstrumentId(long accountId, long instrumentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select position from PortfolioPosition position where position.accountId = :accountId "
            + "and position.instrumentId = :instrumentId")
    Optional<PortfolioPosition> findForUpdate(@Param("accountId") long accountId,
                                               @Param("instrumentId") long instrumentId);

    @Query("select coalesce(sum(position.quantity), 0) from PortfolioPosition position "
            + "where position.instrumentId = :instrumentId")
    long sumQuantityByInstrumentId(@Param("instrumentId") long instrumentId);
}
