package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.MatchParticipant;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@Import(MatchDetailRetentionService.class)
class MatchDetailRetentionServiceTest {

    @Autowired private MatchDetailRetentionService retention;
    @Autowired private SetPieceEventRepository setPieces;
    @Autowired private ShotEventRepository shots;
    @Autowired private MatchPlayerRatingRepository ratings;
    @Autowired private MatchParticipantRepository participants;
    @Autowired private MatchPlanRepository plans;
    @Autowired private CompetitionTeamInfoMatchRepository fixtures;
    @Autowired private ScorerRepository scorers;

    @Test
    @DisplayName("""
            GIVEN set pieces, shots, match ratings, kickoff participants and scorer rows from seasons 1, 2 and 3
            WHEN season 4 starts (and, before that, season 2)
            THEN season 2 prunes nothing; season 4 drops the detail of seasons 1 and 2 and keeps season 3's,
             while every scorer row stays
            """)
    void detailOlderThanThePreviousSeasonIsDropped() {
        for (int season = 1; season <= 3; season++) seasonOfDetail(season);

        assertEquals(new MatchDetailRetentionService.Pruned(0, 0, 0, 0), retention.prune(2));
        MatchDetailRetentionService.Pruned pruned = retention.prune(4);

        assertEquals(new MatchDetailRetentionService.Pruned(2, 2, 2, 2), pruned);
        assertEquals(1, setPieces.count());
        assertEquals(1, shots.count());
        assertEquals(1, ratings.count());
        assertEquals(1, participants.count());
        assertEquals(3, scorers.count());
    }

    private void seasonOfDetail(int season) {
        SetPieceEvent setPiece = new SetPieceEvent();
        setPiece.setSeasonNumber(season);
        setPiece.setMatchStatsId(season); // (match, team, index) is unique
        setPieces.save(setPiece);
        ShotEvent shot = new ShotEvent();
        shot.setSeasonNumber(season);
        shot.setMatchStatsId(season);
        shots.save(shot);
        MatchPlayerRating rating = new MatchPlayerRating();
        rating.setSeasonNumber(season);
        ratings.save(rating);
        Scorer scorer = new Scorer();
        scorer.setSeasonNumber(season);
        scorers.save(scorer);

        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setSeasonNumber(String.valueOf(season));
        fixture = fixtures.save(fixture);
        MatchPlan plan = plans.save(new MatchPlan(MatchPlanService.competitionFixtureKey(fixture.getId()), 1L, "v1",
                10L, 20L, 1, 0, -1, -1, -1, -1, List.of()));
        participants.save(MatchParticipant.of(plan, 10L, 0, true,
                new Contributor(100L, "P", "ST", 15.0, 10, 10, 10, 100.0, false, false)));
    }
}
