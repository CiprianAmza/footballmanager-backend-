package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchEventRepository extends JpaRepository<MatchEvent, Long> {

    List<MatchEvent> findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
            long compId, int season, int round, long team1, long team2);

    List<MatchEvent> findAllByCompetitionIdAndSeasonNumberAndRoundNumber(
            long compId, int season, int round);

    void deleteAllBySeasonNumber(int seasonNumber);

    long countByPlayerIdAndCompetitionIdAndSeasonNumberAndEventType(
            long playerId, long competitionId, int seasonNumber, String eventType);

    List<MatchEvent> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    List<MatchEvent> findAllBySeasonNumber(int seasonNumber);

    /** Canonical events already persisted for a fixture — used for idempotent replay. */
    List<MatchEvent> findByFixtureKey(String fixtureKey);

    /** Canonical events in stable timeline order: goal before its optional assist. */
    List<MatchEvent> findByFixtureKeyOrderBySlotIndexAscEventOrderAsc(String fixtureKey);

    /** Goal events of every match one club played in a season (Schedule goalscorers view). */
    @Query("SELECT e FROM MatchEvent e WHERE e.seasonNumber = :seasonNumber AND e.eventType = 'goal' "
            + "AND (e.teamId1 = :teamId OR e.teamId2 = :teamId)")
    List<MatchEvent> findGoalsBySeasonNumberAndTeamId(@Param("seasonNumber") int seasonNumber,
                                                      @Param("teamId") long teamId);
}
