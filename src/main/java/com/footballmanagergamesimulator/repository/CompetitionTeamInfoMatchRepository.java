package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompetitionTeamInfoMatchRepository extends JpaRepository<CompetitionTeamInfoMatch, Long> {

    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.seasonNumber = :seasonNumber AND ((c.team1Id = :teamId) OR (c.team2Id = :teamId))")
    List<CompetitionTeamInfoMatch> findAllBySeasonNumberAndTeamId(@Param("seasonNumber") String seasonNumber, @Param("teamId") long teamId);

    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE ((c.team1Id = :teamA AND c.team2Id = :teamB) OR (c.team1Id = :teamB AND c.team2Id = :teamA))")
    List<CompetitionTeamInfoMatch> findAllHeadToHead(@Param("teamA") long teamA, @Param("teamB") long teamB);

    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.competitionId = :competitionId AND c.round = :round AND c.seasonNumber = :seasonNumber")
    List<CompetitionTeamInfoMatch> findAllByCompetitionIdAndRoundAndSeasonNumber(
            @Param("competitionId") long competitionId,
            @Param("round") long round,
            @Param("seasonNumber") String seasonNumber);

    @Query("SELECT DISTINCT c.round FROM CompetitionTeamInfoMatch c WHERE c.competitionId = :competitionId AND c.seasonNumber = :seasonNumber")
    List<Long> findDistinctRoundsByCompetitionIdAndSeasonNumber(
            @Param("competitionId") long competitionId,
            @Param("seasonNumber") String seasonNumber);
}
