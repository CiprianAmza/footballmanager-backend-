package com.footballmanagergamesimulator.repository;


import com.footballmanagergamesimulator.model.Scorer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScorerRepository extends JpaRepository<Scorer, Long> {

    /**
     * Compact source of truth used to repair leaderboard rows that were not
     * created when a player was generated or promoted. Keeping the aggregation
     * in SQL avoids loading hundreds of thousands of historical appearances
     * into the JVM during a save-game repair.
     */
    interface LeaderboardAggregate {
        Long getPlayerId();
        Long getMatches();
        Long getGoals();
        Long getLeagueMatches();
        Long getLeagueGoals();
        Long getCupMatches();
        Long getCupGoals();
        Long getSecondLeagueMatches();
        Long getSecondLeagueGoals();
        Long getCurrentSeasonGames();
        Long getCurrentSeasonGoals();
        Long getCurrentSeasonLeagueGames();
        Long getCurrentSeasonLeagueGoals();
        Long getCurrentSeasonCupGames();
        Long getCurrentSeasonCupGoals();
        Long getCurrentSeasonSecondLeagueGames();
        Long getCurrentSeasonSecondLeagueGoals();
    }

    /** One compact row per (competition, season, team, player). */
    interface RatingImpactHistoryAggregate {
        Long getCompetitionId();
        String getCompetitionName();
        Integer getCompetitionTypeId();
        Integer getSeasonNumber();
        Long getTeamId();
        String getTeamName();
        Long getPlayerId();
        Long getAppearances();
        Long getRatingCount();
        Double getRatingTotal();
    }

    @Query("""
            select s.playerId as playerId,
                   count(s.id) as matches,
                   coalesce(sum(s.goals), 0) as goals,
                   coalesce(sum(case when s.competitionTypeId = 1 then 1 else 0 end), 0) as leagueMatches,
                   coalesce(sum(case when s.competitionTypeId = 1 then s.goals else 0 end), 0) as leagueGoals,
                   coalesce(sum(case when s.competitionTypeId = 2 then 1 else 0 end), 0) as cupMatches,
                   coalesce(sum(case when s.competitionTypeId = 2 then s.goals else 0 end), 0) as cupGoals,
                   coalesce(sum(case when s.competitionTypeId = 3 then 1 else 0 end), 0) as secondLeagueMatches,
                   coalesce(sum(case when s.competitionTypeId = 3 then s.goals else 0 end), 0) as secondLeagueGoals,
                   coalesce(sum(case when s.seasonNumber = :season then 1 else 0 end), 0) as currentSeasonGames,
                   coalesce(sum(case when s.seasonNumber = :season then s.goals else 0 end), 0) as currentSeasonGoals,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 1 then 1 else 0 end), 0) as currentSeasonLeagueGames,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 1 then s.goals else 0 end), 0) as currentSeasonLeagueGoals,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 2 then 1 else 0 end), 0) as currentSeasonCupGames,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 2 then s.goals else 0 end), 0) as currentSeasonCupGoals,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 3 then 1 else 0 end), 0) as currentSeasonSecondLeagueGames,
                   coalesce(sum(case when s.seasonNumber = :season and s.competitionTypeId = 3 then s.goals else 0 end), 0) as currentSeasonSecondLeagueGoals
              from Scorer s
             group by s.playerId
            """)
    List<LeaderboardAggregate> aggregateAllForLeaderboard(@Param("season") int season);

    @Query("""
            select s.competitionId as competitionId,
                   max(s.competitionName) as competitionName,
                   s.competitionTypeId as competitionTypeId,
                   s.seasonNumber as seasonNumber,
                   s.teamId as teamId,
                   max(s.teamName) as teamName,
                   s.playerId as playerId,
                   count(s.id) as appearances,
                   coalesce(sum(case when s.rating >= 1.0 and s.rating <= 10.0 then 1 else 0 end), 0) as ratingCount,
                   coalesce(sum(case when s.rating >= 1.0 and s.rating <= 10.0 then s.rating else 0.0 end), 0.0) as ratingTotal
              from Scorer s
             where s.teamScore >= 0
               and s.opponentTeamId >= 0
             group by s.competitionId, s.competitionTypeId, s.seasonNumber, s.teamId, s.playerId
            """)
    List<RatingImpactHistoryAggregate> aggregateRatingImpactHistory();

    List<Scorer> findByPlayerIdAndSeasonNumber(long playerId, int seasonNumber);
    List<Scorer> findAllByPlayerId(long scorerId);
    List<Scorer> findAllByCompetitionId(long competitionId);
    List<Scorer> findAllByTeamId(long teamId);
    List<Scorer> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
    List<Scorer> findAllBySeasonNumber(int seasonNumber);
    List<Scorer> findAllBySeasonNumberAndRoundNumberGreaterThan(int seasonNumber, int roundNumber);

    List<Scorer> findAllByCompetitionIdAndSeasonNumberAndTeamIdAndOpponentTeamId(
            long competitionId, int seasonNumber, long teamId, long opponentTeamId);

    List<Scorer> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamIdAndOpponentTeamId(
            long competitionId, int seasonNumber, int roundNumber, long teamId, long opponentTeamId);

    List<Scorer> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(
            long competitionId, int seasonNumber, int roundNumber, long teamId);

    List<Scorer> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    List<Scorer> findTop5ByPlayerIdOrderByIdDesc(long playerId);

    List<Scorer> findAllByPlayerIdAndCompetitionTypeId(long playerId, int competitionTypeId);

    @Query("select distinct s.seasonNumber from Scorer s where s.teamScore >= 0 order by s.seasonNumber")
    List<Integer> findDistinctSeasonNumbersWithMatches();

}
