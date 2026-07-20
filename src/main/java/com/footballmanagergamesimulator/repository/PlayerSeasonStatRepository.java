package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerSeasonStatRepository extends JpaRepository<PlayerSeasonStat, Long> {

    Optional<PlayerSeasonStat> findByPlayerIdAndCompetitionIdAndSeasonNumber(
            long playerId, long competitionId, int seasonNumber);

    /** All accumulated stats for a (competition, season) — used to build percentile peer pools. */
    List<PlayerSeasonStat> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    /** All competitions for one season — used by the global Overview statistics page. */
    List<PlayerSeasonStat> findAllBySeasonNumber(int seasonNumber);
}
