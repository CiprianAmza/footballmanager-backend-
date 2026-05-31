package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.MatchPlayerRating;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.MatchPlayerRatingRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.LineupRatingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the per-player LineupRatingService rating is persisted per match and is readable,
 * that it matches the engine's aggregate ({@code getBestElevenRatingByTactic} == sum of rows),
 * and that re-persisting the same match+team is idempotent (no duplicate rows).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.tactical-model.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("MatchPlayerRating — per-player rating persists + reads back per match")
class MatchPlayerRatingPersistenceIT {

    @Autowired private LineupRatingService lineupRatingService;
    @Autowired private MatchPlayerRatingRepository ratingRepo;
    @Autowired private GameStateService gameState;
    @Autowired private CompetitionTeamInfoRepository ctiRepo;

    @Test
    void persistPlayerRatings_writesPerStarterRows_thatMatchAggregateAndReadBack() {
        long competitionId = 9001L;
        int season = 99;
        int round = 7;
        long teamId = aLeague().get(0);

        lineupRatingService.persistPlayerRatings(competitionId, season, round, teamId, "442");

        List<MatchPlayerRating> rows = ratingRepo
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(competitionId, season, round, teamId);

        // A real starting XI: between 1 and 11 starters (some may be injured/missing).
        assertThat(rows).isNotEmpty();
        assertThat(rows.size()).isBetween(1, 11);

        double summed = 0;
        for (MatchPlayerRating row : rows) {
            assertThat(row.getPlayerId()).isPositive();
            assertThat(row.getPlayerName()).isNotBlank();
            assertThat(row.getPosition()).isNotBlank();
            assertThat(row.getRating()).isGreaterThan(0.0);
            assertThat(row.getAge()).isPositive();
            assertThat(row.getNationId()).isNotNegative();
            summed += row.getRating();
        }

        // Persisted rows are exactly the per-player decomposition the engine sums.
        double aggregate = lineupRatingService.getBestElevenRatingByTactic(teamId, "442");
        assertThat(summed).isCloseTo(aggregate, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void persistPlayerRatings_isIdempotent_forSameMatchAndTeam() {
        long competitionId = 9002L;
        int season = 98;
        int round = 3;
        long teamId = aLeague().get(0);

        lineupRatingService.persistPlayerRatings(competitionId, season, round, teamId, "442");
        int firstCount = ratingRepo
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(competitionId, season, round, teamId)
                .size();

        lineupRatingService.persistPlayerRatings(competitionId, season, round, teamId, "442");
        int secondCount = ratingRepo
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(competitionId, season, round, teamId)
                .size();

        assertThat(secondCount).isEqualTo(firstCount);
    }

    /** A real league (>= 3 distinct team ids) from the bootstrapped DB for the current season. */
    private List<Long> aLeague() {
        int season = gameState.currentSeason();
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList()) {
            TreeSet<Long> ids = new TreeSet<>();
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
                if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
            if (ids.size() >= 3) return new ArrayList<>(ids);
        }
        throw new IllegalStateException("no league with >=3 teams in the bootstrap");
    }
}
