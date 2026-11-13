package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ShotEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShotEventRepository extends JpaRepository<ShotEvent, Long> {
    List<ShotEvent> findAllByMatchStatsIdOrderByShotIndexAsc(long matchStatsId);
    List<ShotEvent> findAllByTeamIdAndSeasonNumberOrderByRoundNumberAscMatchStatsIdAscShotIndexAsc(
            long teamId, int seasonNumber);
    void deleteAllByMatchStatsId(long matchStatsId);

    /** Detail retention: drops the rows of seasons before {@code season}. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from ShotEvent e where e.seasonNumber < :season")
    int deleteSeasonsBefore(@org.springframework.data.repository.query.Param("season") int season);

}
