package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompetitionTeamInfoDetailRepository extends JpaRepository<CompetitionTeamInfoDetail, Long> {

  List<CompetitionTeamInfoDetail> findAllBySeasonNumber(long seasonNumber);

  List<CompetitionTeamInfoDetail> findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(long competitionId, long roundId, long team1Id, long team2Id, long seasonNumber);

  List<CompetitionTeamInfoDetail> findAllByCompetitionIdAndRoundIdAndSeasonNumber(long competitionId, long roundId, long seasonNumber);

  List<CompetitionTeamInfoDetail> findAllByCompetitionIdAndSeasonNumber(long competitionId, long seasonNumber);

  /**
   * Every persisted fixture result involving one team. These rows are the
   * authoritative source for team W/D/L and score aggregates, including
   * qualifying matches in which no player Scorer row was written.
   */
  @Query("""
      SELECT d FROM CompetitionTeamInfoDetail d
      WHERE d.team1Id = :teamId OR d.team2Id = :teamId
      ORDER BY d.seasonNumber ASC, d.competitionId ASC, d.roundId ASC,
               d.day ASC, d.matchIndex ASC, d.legNumber ASC, d.id ASC
      """)
  List<CompetitionTeamInfoDetail> findAllByTeamId(@Param("teamId") long teamId);

  @Query("""
      SELECT d FROM CompetitionTeamInfoDetail d
      WHERE (d.team1Id = :teamA AND d.team2Id = :teamB)
         OR (d.team1Id = :teamB AND d.team2Id = :teamA)
      ORDER BY d.seasonNumber DESC, d.roundId DESC, d.id DESC
      """)
  List<CompetitionTeamInfoDetail> findAllHeadToHead(
          @Param("teamA") long teamA,
          @Param("teamB") long teamB);
}
