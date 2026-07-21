package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompetitionTeamInfoMatchRepository extends JpaRepository<CompetitionTeamInfoMatch, Long> {

    /** Serializes canonical plan creation for one real fixture across requests/nodes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.id = :id")
    Optional<CompetitionTeamInfoMatch> findByIdForUpdate(@Param("id") long id);

    List<CompetitionTeamInfoMatch> findAllBySeasonNumber(String seasonNumber);

    List<CompetitionTeamInfoMatch> findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(
            long competitionId, String seasonNumber);

    /** Fetch a specific leg of a two-leg tie (used to aggregate leg 2 with the persisted leg 1). */
    Optional<CompetitionTeamInfoMatch> findByTieIdAndLegNumber(long tieId, int legNumber);

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

    // Used by CupBracketService to find the specific bracket slot a winner advances into.
    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.competitionId = :competitionId AND c.round = :round AND c.matchIndex = :matchIndex AND c.seasonNumber = :seasonNumber")
    java.util.Optional<CompetitionTeamInfoMatch> findOneByCompetitionRoundIndex(
            @Param("competitionId") long competitionId,
            @Param("round") long round,
            @Param("matchIndex") int matchIndex,
            @Param("seasonNumber") String seasonNumber);

    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.competitionId = :competitionId "
            + "AND c.round = :round AND c.seasonNumber = :seasonNumber "
            + "AND c.team1Id = :team1Id AND c.team2Id = :team2Id AND c.legNumber = :legNumber")
    List<CompetitionTeamInfoMatch> findPlayedFixture(
            @Param("competitionId") long competitionId,
            @Param("round") long round,
            @Param("seasonNumber") String seasonNumber,
            @Param("team1Id") long team1Id,
            @Param("team2Id") long team2Id,
            @Param("legNumber") int legNumber);
}
