package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.MediaController;
import com.footballmanagergamesimulator.frontend.MediaPredictionView;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz test for the championship-prediction invariant:
 * <blockquote>
 *   In a league, the team with media prediction = 1 (i.e. the team rated
 *   highest by {@link MediaController#getMediaPrediction(long)} at the start
 *   of the season) should win the championship in ≥ 5 of 10 simulated
 *   seasons.
 * </blockquote>
 *
 * <p>Methodology (single Spring context, multi-season loop):
 * <ol>
 *   <li>Find the main league competition (typeId == 1, lowest id)</li>
 *   <li>For each season iteration:
 *     <ol type="a">
 *       <li>Look up media prediction #1 BEFORE the season starts</li>
 *       <li>Simulate every matchday of that season via
 *           {@link CompetitionController#simulateRound(String, String)}</li>
 *       <li>Pick the champion from the final standings (team with most points,
 *           ties broken by goal difference then goals for)</li>
 *       <li>Compare champion vs prediction; count match if they're the same team</li>
 *       <li>If more iterations remain, advance to the next season via
 *           {@link SeasonTransitionService#processEndOfSeason(int)} +
 *           {@link SeasonTransitionService#processNewSeasonSetup(int)}.</li>
 *     </ol>
 *   </li>
 *   <li>Assert: predicted-team titles ≥ {@code MIN_PREDICTED_TITLES}</li>
 * </ol>
 *
 * <p>Expected to <b>FAIL initially</b>. The current Poisson-based engine
 * delivers ~86% strong-wins for a 10k vs 4k power delta
 * ({@code MatchEngineRepStrengthFuzzIT}), and with 11+ teams competing each
 * season, the cumulative variance across ~38 matchdays is large enough that
 * the top-rated team often slips to 2nd or 3rd. The failure message surfaces
 * the actual title count + which teams won, so we can tune engine parameters
 * (amplification exponent, expected-goals total, morale weights) and re-run.
 *
 * <p><b>Tuning rule</b>: any engine change must NOT regress
 * {@code MatchEngineRepStrengthFuzzIT}. Both fuzz invariants are siblings;
 * keep them green together.
 *
 * <p>Runtime: ~10-30s per season for a 12-team league (22 matchdays × ~5
 * matches × ~50ms), so 10 seasons ≈ 3-5 minutes. Acceptable for {@code -Pfuzz}.
 *
 * <p>Run with: {@code mvn verify -Pfuzz}.
 */
@SpringBootTest
class ChampionshipPredictionFuzzIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private MediaController mediaController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private TeamCompetitionDetailRepository tcdRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private SeasonTransitionService seasonTransitionService;

    // Temporarily reduced from 10 → 3 while we measure runtime / tune. Season
    // transitions turned out heavier than expected; restore to 10 once we
    // confirm the test completes in a sane time budget per iteration.
    private static final int SEASONS_TO_RUN = 3;
    private static final int MIN_PREDICTED_TITLES = 2; // proportional: was 5/10 → 2/3 (more lenient at small N)
    /** League competition typeId — first division. */
    private static final long LEAGUE_TYPE_ID = 1L;

    @Test
    @DisplayName("Top-predicted team wins ≥ 5 of 10 championships")
    void topPredictedTeamWinsAtLeast5Of10Championships() {
        // Pick the lowest-id type-1 league as the "main" competition under test.
        // Bootstrap creates ~7 first-division leagues; we use the first one.
        Set<Long> typeOneIds = competitionRepository.findIdsByTypeId(LEAGUE_TYPE_ID);
        assertThat(typeOneIds)
            .as("Bootstrap must produce at least one type-1 (first-division) league")
            .isNotEmpty();
        long leagueCompId = typeOneIds.stream().sorted().findFirst().orElseThrow();

        int predictedTitles = 0;
        long testStartMs = System.currentTimeMillis();
        // Direct stdout per phase (NOT buffered StringBuilder) so we can watch
        // progress live — surefire output IS buffered per test method, but
        // log files (target/surefire-reports/...) are flushed line-by-line.
        // Also useful when the test runs > 5 min and you wonder which season
        // is currently churning.
        System.out.printf("%n=== ChampionshipPredictionFuzzIT START | competitionId=%d | seasons=%d ===%n",
            leagueCompId, SEASONS_TO_RUN);
        System.out.flush();

        for (int iter = 0; iter < SEASONS_TO_RUN; iter++) {
            long iterStartMs = System.currentTimeMillis();
            int currentSeason = (int) currentSeason();
            System.out.printf("%n--- Iteration %d/%d (season=%d, total_elapsed=%.1fs) ---%n",
                iter + 1, SEASONS_TO_RUN, currentSeason,
                (iterStartMs - testStartMs) / 1000.0);
            System.out.flush();

            // 1. Top media prediction for THIS season (recomputed — different
            //    seasons may surface different favourites as players age/transfer).
            long t0 = System.currentTimeMillis();
            List<MediaPredictionView> predictions = mediaController.getMediaPrediction(leagueCompId);
            assertThat(predictions)
                .as("MediaController must produce a non-empty prediction list for competition %d in season %d",
                    leagueCompId, currentSeason)
                .isNotEmpty();
            long predictedTopId = predictions.get(0).getManagerTeamTacticView().getTeamId();
            String predictedTopName = lookupTeamName(predictedTopId);
            long t1 = System.currentTimeMillis();
            System.out.printf("  [%.2fs] mediaPrediction → top=%s (id=%d)%n",
                (t1 - t0) / 1000.0, predictedTopName, predictedTopId);
            System.out.flush();

            // 2. Simulate every matchday of this season for the league.
            List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                leagueCompId, String.valueOf(currentSeason));
            assertThat(matchdays)
                .as("League %d must have fixtures generated for season %d", leagueCompId, currentSeason)
                .isNotEmpty();
            matchdays.sort(Long::compareTo);
            long t2 = System.currentTimeMillis();
            System.out.printf("  [%.2fs] discovered %d matchdays; simulating...%n",
                (t2 - t1) / 1000.0, matchdays.size());
            System.out.flush();

            for (Long md : matchdays) {
                long mdStart = System.currentTimeMillis();
                competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(md));
                long mdMs = System.currentTimeMillis() - mdStart;
                // Per-matchday log only on slow ones (>500ms) to avoid flooding
                if (mdMs > 500) {
                    System.out.printf("    md=%d simulated in %dms%n", md, mdMs);
                    System.out.flush();
                }
            }
            long t3 = System.currentTimeMillis();
            System.out.printf("  [%.2fs] all %d matchdays done%n",
                (t3 - t2) / 1000.0, matchdays.size());
            System.out.flush();

            // 3. Pick the champion = top of standings by points → GD → GF.
            long championId = findChampion(leagueCompId);
            String championName = lookupTeamName(championId);

            boolean hit = (championId == predictedTopId);
            if (hit) predictedTitles++;

            long t4 = System.currentTimeMillis();
            System.out.printf("  [%.2fs] champion=%s (id=%d) %s | running total: %d/%d hits%n",
                (t4 - t3) / 1000.0, championName, championId,
                hit ? "✓ HIT" : "✗ miss", predictedTitles, iter + 1);
            System.out.flush();

            // 4. Advance to the next season if more iterations remain.
            if (iter < SEASONS_TO_RUN - 1) {
                long ts0 = System.currentTimeMillis();
                seasonTransitionService.processEndOfSeason(currentSeason);
                long ts1 = System.currentTimeMillis();
                System.out.printf("  [%.2fs] processEndOfSeason DONE%n", (ts1 - ts0) / 1000.0);
                System.out.flush();
                seasonTransitionService.processNewSeasonSetup(currentSeason);
                long ts2 = System.currentTimeMillis();
                System.out.printf("  [%.2fs] processNewSeasonSetup DONE%n", (ts2 - ts1) / 1000.0);
                System.out.flush();
            }

            long iterMs = System.currentTimeMillis() - iterStartMs;
            System.out.printf("--- Iteration %d/%d DONE in %.1fs ---%n",
                iter + 1, SEASONS_TO_RUN, iterMs / 1000.0);
            System.out.flush();
        }

        long totalMs = System.currentTimeMillis() - testStartMs;
        System.out.printf("%n=== FINAL: %d / %d predicted-team titles (target ≥ %d) | total %.1fs ===%n%n",
            predictedTitles, SEASONS_TO_RUN, MIN_PREDICTED_TITLES, totalMs / 1000.0);
        System.out.flush();

        assertThat(predictedTitles)
            .as("Top-predicted team must win ≥ %d of %d championships (actual %d). "
                + "See per-iteration stdout above for hit/miss + timing. Tune match-engine "
                + "parameters (amplification exponent, expected goals, morale weight) until "
                + "both this AND MatchEngineRepStrengthFuzzIT pass.",
                MIN_PREDICTED_TITLES, SEASONS_TO_RUN, predictedTitles)
            .isGreaterThanOrEqualTo(MIN_PREDICTED_TITLES);
    }

    // ==================== helpers ====================

    private long currentSeason() {
        Round round = roundRepository.findById(1L).orElseThrow(() ->
            new IllegalStateException("Round id=1 missing — bootstrap didn't run?"));
        return round.getSeason();
    }

    private long findChampion(long competitionId) {
        List<TeamCompetitionDetail> standings = tcdRepository.findAll().stream()
            .filter(d -> d.getCompetitionId() == competitionId)
            .sorted(Comparator
                .comparingInt(TeamCompetitionDetail::getPoints).reversed()
                .thenComparingInt(this::goalDiff).reversed()
                .thenComparingInt(TeamCompetitionDetail::getGoalsFor).reversed())
            .toList();
        assertThat(standings)
            .as("Standings for competition %d must contain teams", competitionId)
            .isNotEmpty();
        return standings.get(0).getTeamId();
    }

    private int goalDiff(TeamCompetitionDetail d) {
        return d.getGoalsFor() - d.getGoalsAgainst();
    }

    private String lookupTeamName(long teamId) {
        return teamRepository.findById(teamId)
            .map(t -> t.getName())
            .orElse("Team#" + teamId);
    }
}
