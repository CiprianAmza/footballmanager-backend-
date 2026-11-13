package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompetitionTeamInfoDetailRepository extends JpaRepository<CompetitionTeamInfoDetail, Long> {

  interface TeamRoundAggregate {
    Long getSeasonNumber();
    Long getCompetitionId();
    Long getTeamId();
    Long getLatestRound();
  }

  interface ManagerMatchAggregate {
    Long getSeasonNumber();
    Long getCompetitionId();
    Long getTeamId();
    Long getManagerId();
    Long getMatches();
  }

  @Query("""
      select d.seasonNumber as seasonNumber, d.competitionId as competitionId,
             d.team1Id as teamId, d.manager1Id as managerId, count(d.id) as matches
        from CompetitionTeamInfoDetail d
       where d.manager1Id is not null and (:season is null or d.seasonNumber = :season)
       group by d.seasonNumber, d.competitionId, d.team1Id, d.manager1Id
      """)
  List<ManagerMatchAggregate> aggregateHomeManagers(@Param("season") Long season);

  @Query("""
      select d.seasonNumber as seasonNumber, d.competitionId as competitionId,
             d.team2Id as teamId, d.manager2Id as managerId, count(d.id) as matches
        from CompetitionTeamInfoDetail d
       where d.manager2Id is not null and (:season is null or d.seasonNumber = :season)
       group by d.seasonNumber, d.competitionId, d.team2Id, d.manager2Id
      """)
  List<ManagerMatchAggregate> aggregateAwayManagers(@Param("season") Long season);

  @Query("""
      select d.seasonNumber as seasonNumber, d.competitionId as competitionId,
             d.team1Id as teamId, max(d.roundId) as latestRound
        from CompetitionTeamInfoDetail d
       where :season is null or d.seasonNumber = :season
       group by d.seasonNumber, d.competitionId, d.team1Id
      """)
  List<TeamRoundAggregate> aggregateHomeRounds(@Param("season") Long season);

  @Query("""
      select d.seasonNumber as seasonNumber, d.competitionId as competitionId,
             d.team2Id as teamId, max(d.roundId) as latestRound
        from CompetitionTeamInfoDetail d
       where :season is null or d.seasonNumber = :season
       group by d.seasonNumber, d.competitionId, d.team2Id
      """)
  List<TeamRoundAggregate> aggregateAwayRounds(@Param("season") Long season);

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

  /** One club's results in one season; survives the per-season fixture wipe. */
  @Query("""
      SELECT d FROM CompetitionTeamInfoDetail d
      WHERE d.seasonNumber = :seasonNumber AND (d.team1Id = :teamId OR d.team2Id = :teamId)
      ORDER BY d.id ASC
      """)
  List<CompetitionTeamInfoDetail> findAllBySeasonNumberAndTeamId(
          @Param("seasonNumber") long seasonNumber,
          @Param("teamId") long teamId);

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
