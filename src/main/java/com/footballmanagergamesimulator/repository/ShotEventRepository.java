package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ShotEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShotEventRepository extends JpaRepository<ShotEvent, Long> {
    List<ShotEvent> findAllByMatchStatsIdOrderByShotIndexAsc(long matchStatsId);
    List<ShotEvent> findAllByTeamIdAndSeasonNumberOrderByRoundNumberAscMatchStatsIdAscShotIndexAsc(
            long teamId, int seasonNumber);
    void deleteAllByMatchStatsId(long matchStatsId);
}
