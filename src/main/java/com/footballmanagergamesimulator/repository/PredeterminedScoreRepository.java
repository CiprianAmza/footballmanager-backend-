package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PredeterminedScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PredeterminedScoreRepository extends JpaRepository<PredeterminedScore, Long> {

    Optional<PredeterminedScore> findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2IdAndConsumedFalse(
            long competitionId, int seasonNumber, int roundNumber, long team1Id, long team2Id);

    // Returns all currently-set (un-consumed) predetermined scores for the admin panel listing.
    List<PredeterminedScore> findAllByConsumedFalse();
}
