package com.footballmanagergamesimulator.repository;


import com.footballmanagergamesimulator.model.Scorer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScorerRepository extends JpaRepository<Scorer, Long> {

    List<Scorer> findByPlayerIdAndSeasonNumber(long playerId, int seasonNumber);
    List<Scorer> findAllByPlayerId(long scorerId);
    List<Scorer> findAllByCompetitionId(long competitionId);
    List<Scorer> findAllByTeamId(long teamId);
    List<Scorer> findAllBySeasonNumber(int seasonNumber);

}
