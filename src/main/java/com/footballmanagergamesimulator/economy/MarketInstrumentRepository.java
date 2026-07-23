package com.footballmanagergamesimulator.economy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MarketInstrumentRepository extends JpaRepository<MarketInstrument, Long> {
    List<MarketInstrument> findAllByActiveTrueOrderByCodeAsc();
    Optional<MarketInstrument> findByCode(String code);
    Optional<MarketInstrument> findByTeamId(Long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instrument from MarketInstrument instrument where instrument.id = :instrumentId")
    Optional<MarketInstrument> findByIdForUpdate(@Param("instrumentId") long instrumentId);
}
