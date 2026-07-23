package com.footballmanagergamesimulator.economy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketPriceSnapshotRepository extends JpaRepository<MarketPriceSnapshot, Long> {
    Optional<MarketPriceSnapshot> findByInstrumentIdAndSeasonNumberAndGameDay(
            long instrumentId, int seasonNumber, int gameDay);
    Optional<MarketPriceSnapshot> findTopByInstrumentIdOrderBySeasonNumberDescGameDayDesc(long instrumentId);
    List<MarketPriceSnapshot> findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
            long instrumentId, Pageable pageable);
}
