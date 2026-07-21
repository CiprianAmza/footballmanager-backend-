package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Scorer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class ScorerRepositoryLeaderboardAggregateTest {

    @Autowired
    private ScorerRepository scorerRepository;

    @Test
    void aggregatesHistoricAndCurrentSeasonCountersInTheDatabase() {
        scorerRepository.save(appearance(10L, 1, 1, 2));
        scorerRepository.save(appearance(10L, 2, 2, 1));
        scorerRepository.save(appearance(10L, 2, 4, 3));

        List<ScorerRepository.LeaderboardAggregate> rows =
                scorerRepository.aggregateAllForLeaderboard(2);

        assertEquals(1, rows.size());
        ScorerRepository.LeaderboardAggregate row = rows.get(0);
        assertEquals(10L, row.getPlayerId());
        assertEquals(3L, row.getMatches());
        assertEquals(6L, row.getGoals());
        assertEquals(1L, row.getLeagueMatches());
        assertEquals(2L, row.getLeagueGoals());
        assertEquals(1L, row.getCupMatches());
        assertEquals(1L, row.getCupGoals());
        assertEquals(2L, row.getCurrentSeasonGames());
        assertEquals(4L, row.getCurrentSeasonGoals());
    }

    @Test
    void aggregatesRatingHistoryPerCompetitionSeasonTeamAndPlayer() {
        scorerRepository.save(ratedAppearance(10L, 7L, "Sherlock FC", 4L,
                "League of Champions", 4, 3, 7.2));
        scorerRepository.save(ratedAppearance(10L, 7L, "Sherlock FC", 4L,
                "League of Champions", 4, 3, 8.4));
        scorerRepository.save(ratedAppearance(11L, 7L, "Sherlock FC", 4L,
                "League of Champions", 4, 3, 6.8));

        List<ScorerRepository.RatingImpactHistoryAggregate> rows =
                scorerRepository.aggregateRatingImpactHistory();

        assertEquals(2, rows.size());
        ScorerRepository.RatingImpactHistoryAggregate player = rows.stream()
                .filter(row -> row.getPlayerId() == 10L)
                .findFirst()
                .orElseThrow();
        assertEquals(4L, player.getCompetitionId());
        assertEquals("League of Champions", player.getCompetitionName());
        assertEquals(4, player.getCompetitionTypeId());
        assertEquals(3, player.getSeasonNumber());
        assertEquals(7L, player.getTeamId());
        assertEquals("Sherlock FC", player.getTeamName());
        assertEquals(2L, player.getAppearances());
        assertEquals(2L, player.getRatingCount());
        assertEquals(15.6, player.getRatingTotal(), 0.001);
    }

    @Test
    void aggregatesSingleSeasonAndAllTimeCompetitionRecordsInTheDatabase() {
        scorerRepository.save(recordAppearance(10L, 7L, "Sherlock FC", 4L, 2, 3, 1));
        scorerRepository.save(recordAppearance(10L, 7L, "Sherlock FC", 4L, 2, 2, 2));
        scorerRepository.save(recordAppearance(10L, 8L, "Xenon", 4L, 3, 4, 6));
        scorerRepository.save(recordAppearance(11L, 9L, "Technoid", 4L, 4, 6, 1));
        scorerRepository.save(recordAppearance(12L, 9L, "Technoid", 99L, 4, 50, 50));

        List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonGoals =
                scorerRepository.findCompetitionSeasonGoalRecords(4L, PageRequest.of(0, 10));
        List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonAssists =
                scorerRepository.findCompetitionSeasonAssistRecords(4L, PageRequest.of(0, 10));
        List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeGoals =
                scorerRepository.findCompetitionAllTimeGoalRecords(4L, PageRequest.of(0, 10));
        List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeAssists =
                scorerRepository.findCompetitionAllTimeAssistRecords(4L, PageRequest.of(0, 10));

        assertEquals(3, seasonGoals.size());
        assertEquals(11L, seasonGoals.get(0).getPlayerId());
        assertEquals(6L, seasonGoals.get(0).getGoals());
        assertEquals(4, seasonGoals.get(0).getSeasonNumber());
        assertEquals(10L, seasonAssists.get(0).getPlayerId());
        assertEquals(6L, seasonAssists.get(0).getAssists());
        assertEquals(3, seasonAssists.get(0).getSeasonNumber());

        assertEquals(2, allTimeGoals.size());
        assertEquals(10L, allTimeGoals.get(0).getPlayerId());
        assertEquals(9L, allTimeGoals.get(0).getGoals());
        assertEquals(9L, allTimeGoals.get(0).getAssists());
        assertEquals(3L, allTimeGoals.get(0).getAppearances());
        assertEquals(2L, allTimeGoals.get(0).getTeamCount());
        assertEquals(2, allTimeGoals.get(0).getFirstSeason());
        assertEquals(3, allTimeGoals.get(0).getLastSeason());
        assertEquals(10L, allTimeAssists.get(0).getPlayerId());
    }

    private Scorer appearance(long playerId, int season, int type, int goals) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setSeasonNumber(season);
        scorer.setCompetitionTypeId(type);
        scorer.setGoals(goals);
        scorer.setTeamScore(goals);
        return scorer;
    }

    private Scorer ratedAppearance(long playerId, long teamId, String teamName,
                                   long competitionId, String competitionName,
                                   int competitionTypeId, int season, double rating) {
        Scorer scorer = appearance(playerId, season, competitionTypeId, 0);
        scorer.setTeamId(teamId);
        scorer.setTeamName(teamName);
        scorer.setOpponentTeamId(99L);
        scorer.setCompetitionId(competitionId);
        scorer.setCompetitionName(competitionName);
        scorer.setRating(rating);
        return scorer;
    }

    private Scorer recordAppearance(long playerId, long teamId, String teamName,
                                    long competitionId, int season, int goals, int assists) {
        Scorer scorer = appearance(playerId, season, 4, goals);
        scorer.setTeamId(teamId);
        scorer.setTeamName(teamName);
        scorer.setOpponentTeamId(99L);
        scorer.setCompetitionId(competitionId);
        scorer.setCompetitionName("League of Champions");
        scorer.setAssists(assists);
        return scorer;
    }
}
