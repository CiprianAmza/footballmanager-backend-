package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Scorer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

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

    private Scorer appearance(long playerId, int season, int type, int goals) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setSeasonNumber(season);
        scorer.setCompetitionTypeId(type);
        scorer.setGoals(goals);
        scorer.setTeamScore(goals);
        return scorer;
    }
}
