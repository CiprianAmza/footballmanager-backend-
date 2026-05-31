package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchPlayerRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchPlayerRatingRepository extends JpaRepository<MatchPlayerRating, Long> {

  List<MatchPlayerRating> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(
          long competitionId, int seasonNumber, int roundNumber, long teamId);
}
