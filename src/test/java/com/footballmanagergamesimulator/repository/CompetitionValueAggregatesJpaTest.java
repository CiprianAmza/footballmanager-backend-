package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Scorer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:value-aggregates;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CompetitionValueAggregatesJpaTest {
    @Autowired private ScorerRepository scorers;
    @Autowired private CompetitionTeamInfoDetailRepository results;

    @Test
    void aggregatesPlayerContributionAndCupStageBySeasonAndTeam() {
        scorer(2, 7, 11, 101, 2, 1);
        scorer(2, 7, 11, 101, 1, 0);
        scorer(3, 7, 11, 101, 5, 0);
        var season = scorers.aggregateForValueRankings(2);
        assertEquals(1, season.size());
        assertEquals(3L, season.get(0).getGoals());
        assertEquals(1L, season.get(0).getAssists());
        assertEquals(2L, season.get(0).getAppearances());
        assertEquals(2, scorers.aggregateForValueRankings(null).size());

        result(2, 7, 11, 22, 1);
        result(2, 7, 33, 11, 3);
        result(3, 7, 11, 44, 4);
        long latestHome = results.aggregateHomeRounds(2L).stream()
                .filter(row -> row.getTeamId() == 11).findFirst().orElseThrow().getLatestRound();
        long latestAway = results.aggregateAwayRounds(2L).stream()
                .filter(row -> row.getTeamId() == 11).findFirst().orElseThrow().getLatestRound();
        assertEquals(1L, latestHome);
        assertEquals(3L, latestAway);
        assertTrue(results.aggregateHomeRounds(null).size() > results.aggregateHomeRounds(2L).size());
        assertEquals(1L, results.aggregateHomeManagers(2L).get(0).getMatches());
        assertEquals(101L, results.aggregateHomeManagers(2L).get(0).getManagerId());
    }

    private void scorer(int season, long comp, long team, long player, int goals, int assists) {
        Scorer row = new Scorer(); row.setSeasonNumber(season); row.setCompetitionId(comp);
        row.setTeamId(team); row.setPlayerId(player); row.setGoals(goals); row.setAssists(assists);
        scorers.save(row);
    }

    private void result(int season, long comp, long home, long away, long round) {
        CompetitionTeamInfoDetail row = new CompetitionTeamInfoDetail();
        row.setSeasonNumber(season); row.setCompetitionId(comp); row.setTeam1Id(home);
        row.setTeam2Id(away); row.setRoundId(round); results.save(row);
        if (season == 2 && home == 11) { row.setManager1Id(101L); results.save(row); }
    }
}
