package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchPlayerRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface MatchPlayerRatingRepository extends JpaRepository<MatchPlayerRating, Long> {

  List<MatchPlayerRating> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(
          long competitionId, int seasonNumber, int roundNumber, long teamId);

  List<MatchPlayerRating> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamIdIn(
          long competitionId, int seasonNumber, int roundNumber, Set<Long> teamIds);

  List<MatchPlayerRating> findAllByCompetitionIdAndSeasonNumber(
          long competitionId, int seasonNumber);

  List<MatchPlayerRating> findAllBySeasonNumber(int seasonNumber);
}
