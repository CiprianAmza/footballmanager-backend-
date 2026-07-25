package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.PredeterminedScore;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.service.TeamPostMatchService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Written for the H2 concurrency gate; execution is policy-disabled for implementation work. */
@Disabled("NOT_RUN_BY_IMPLEMENTER_POLICY")
@SpringBootTest
class Phase13AdminOverrideLockOrderIT {
    @Autowired private PredeterminedScoreRepository predeterminedScores;
    @Autowired private TeamPostMatchService postMatchService;

    @Test
    void newDecisionAndPersistedReplayCompleteWithoutDeadlockAndConsumeOnce() {
        PredeterminedScore row = new PredeterminedScore();
        row.setCompetitionId(901L);
        row.setSeasonNumber(7);
        row.setRoundNumber(3);
        row.setTeam1Id(11L);
        row.setTeam2Id(12L);
        row.setTeam1Score(2);
        row.setTeam2Score(1);
        predeterminedScores.saveAndFlush(row);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<TeamPostMatchService.PredeterminedScoreAttempt>> calls = List.of(
                    pool.submit(() -> postMatchService.consumePredeterminedScoreIfMatches(
                            901L, 7, 3, 11L, 12L, 2, 1)),
                    pool.submit(() -> postMatchService.consumePredeterminedScoreIfMatches(
                            901L, 7, 3, 11L, 12L, 2, 1)));
            List<TeamPostMatchService.PredeterminedScoreAttempt> results = assertTimeoutPreemptively(
                    Duration.ofSeconds(10), () -> calls.stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).toList());
            assertThat(results).extracting(TeamPostMatchService.PredeterminedScoreAttempt::resolution)
                    .containsExactlyInAnyOrder(
                            TeamPostMatchService.PredeterminedScoreResolution.CONSUMED,
                            TeamPostMatchService.PredeterminedScoreResolution.ALREADY_CONSUMED);
            assertThat(predeterminedScores.findAll()).hasSize(1);
            assertThat(predeterminedScores.findAll().get(0).isConsumed()).isTrue();
        } catch (Exception exception) {
            throw new AssertionError("concurrent override claim failed", exception);
        } finally {
            pool.shutdownNow();
        }
    }
}
