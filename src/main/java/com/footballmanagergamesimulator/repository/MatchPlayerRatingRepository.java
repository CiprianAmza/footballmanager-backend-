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

    /** Detail retention: drops the rows of seasons before {@code season}. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from MatchPlayerRating e where e.seasonNumber < :season")
    int deleteSeasonsBefore(@org.springframework.data.repository.query.Param("season") int season);

}
