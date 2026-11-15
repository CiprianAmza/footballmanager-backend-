package com.footballmanagergamesimulator.match2d;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface Match2dReplayRepository extends JpaRepository<Match2dReplay, Long> {

    Optional<Match2dReplay> findByFixtureKey(String fixtureKey);

    Optional<Match2dReplay> findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
            long competitionId, int season, int round, long team1Id, long team2Id);

    boolean existsByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
            long competitionId, int season, int round, long team1Id, long team2Id);
}
