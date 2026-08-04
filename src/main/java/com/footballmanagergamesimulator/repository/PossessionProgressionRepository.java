package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PossessionProgression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PossessionProgressionRepository extends JpaRepository<PossessionProgression, Long> {
    List<PossessionProgression> findAllByMatchStatsIdOrderByTeamIdAsc(long matchStatsId);
    List<PossessionProgression> findAllByTeamIdAndSeasonNumberOrderByRoundNumberAscMatchStatsIdAsc(
            long teamId, int seasonNumber);
    void deleteAllByMatchStatsId(long matchStatsId);
}
