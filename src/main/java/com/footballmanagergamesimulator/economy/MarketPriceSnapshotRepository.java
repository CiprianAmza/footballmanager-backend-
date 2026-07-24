package com.footballmanagergamesimulator.economy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MarketPriceSnapshotRepository extends JpaRepository<MarketPriceSnapshot, Long> {
    Optional<MarketPriceSnapshot> findByInstrumentIdAndSeasonNumberAndGameDay(
            long instrumentId, int seasonNumber, int gameDay);
    Optional<MarketPriceSnapshot> findTopByInstrumentIdOrderBySeasonNumberDescGameDayDesc(long instrumentId);
    List<MarketPriceSnapshot> findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
            long instrumentId, Pageable pageable);

    @Query("select snapshot from MarketPriceSnapshot snapshot where snapshot.instrumentId = :instrumentId "
            + "and (snapshot.seasonNumber < :season or "
            + "(snapshot.seasonNumber = :season and snapshot.gameDay <= :day)) "
            + "order by snapshot.seasonNumber desc, snapshot.gameDay desc")
    List<MarketPriceSnapshot> findObservedThrough(@Param("instrumentId") long instrumentId,
                                                  @Param("season") int season,
                                                  @Param("day") int day,
                                                  Pageable pageable);
}
