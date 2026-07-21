package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface MatchStatsRepository extends JpaRepository<MatchStats, Long> {

    Optional<MatchStats> findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
            long competitionId, int seasonNumber, int roundNumber, long team1Id, long team2Id);

    List<MatchStats> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    List<MatchStats> findAllByCompetitionIdIn(Collection<Long> competitionIds);

    List<MatchStats> findAllBySeasonNumber(int seasonNumber);

    List<MatchStats> findAllByTeam1IdAndSeasonNumber(long team1Id, int seasonNumber);

    List<MatchStats> findAllByTeam2IdAndSeasonNumber(long team2Id, int seasonNumber);
}
