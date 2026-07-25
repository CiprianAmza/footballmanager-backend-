package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.PersistedScoringPlan;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PredeterminedScore;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchAppearanceRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.service.TeamPostMatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Full Spring/H2 lock-order gate; opt-in because it exercises real transactions and effects. */
@EnabledIfSystemProperty(named = "compartment.phase13.concurrency", matches = "true")
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.match-plan.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:phase13lockorder;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000"
})
class Phase13AdminOverrideLockOrderIT {
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private PredeterminedScoreRepository predeterminedScores;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private MatchAppearanceRepository appearanceRepository;
    @Autowired private MatchPlanService matchPlanService;
    @Autowired private TeamPostMatchService postMatchService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void newDecisionAndPersistedReplayCompleteWithoutDeadlockAndDuplicateEffects() throws Exception {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(901L);
        fixture.setSeasonNumber("7");
        fixture.setRound(3L);
        fixture.setTeam1Id(11L);
        fixture.setTeam2Id(12L);
        fixture = fixtureRepository.saveAndFlush(fixture);
        long fixtureId = fixture.getId();
        String fixtureKey = MatchPlanService.competitionFixtureKey(fixtureId);
        PredeterminedScore override = new PredeterminedScore();
        override.setCompetitionId(901L);
        override.setSeasonNumber(7);
        override.setRoundNumber(3);
        override.setTeam1Id(11L);
        override.setTeam2Id(12L);
        override.setTeam1Score(2);
        override.setTeam2Score(1);
        predeterminedScores.saveAndFlush(override);

        MatchScoringDecision decision = new MatchScoringDecision(fixtureKey, 77L,
                ScoreEngineKind.ADMIN_OVERRIDE, ScoreEngineKind.ADMIN_OVERRIDE.algorithmVersion(),
                "a".repeat(64), "b".repeat(64), 2, 1, 40, 34, null, null);
        CountDownLatch decisionPersisted = new CountDownLatch(1);
        CyclicBarrier claimStart = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<RunnerResult> creator = workers.submit(() -> {
                PersistedScoringPlan adopted = matchPlanService.persistOrLoadScoreDecision(
                        decision, 11L, 12L, KnockoutPlanSplit.regularOnly(2, 1));
                decisionPersisted.countDown();
                claimStart.await(10, TimeUnit.SECONDS);
                return finishWithRealFixtureLock(adopted.decision(), fixtureKey, 11L, 12L);
            });
            Future<RunnerResult> replay = workers.submit(() -> {
                if (!decisionPersisted.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("decision was not persisted in time");
                }
                PersistedScoringPlan adopted = matchPlanService.findPersistedScoringPlan(
                        fixtureKey, 11L, 12L).orElseThrow();
                claimStart.await(10, TimeUnit.SECONDS);
                return finishWithRealFixtureLock(adopted.decision(), fixtureKey, 11L, 12L);
            });

            List<RunnerResult> results = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> List.of(creator.get(10, TimeUnit.SECONDS), replay.get(10, TimeUnit.SECONDS)));
            assertThat(results).extracting(RunnerResult::claim)
                    .extracting(TeamPostMatchService.PredeterminedScoreAttempt::resolution)
                    .containsExactlyInAnyOrder(
                            TeamPostMatchService.PredeterminedScoreResolution.CONSUMED,
                            TeamPostMatchService.PredeterminedScoreResolution.ALREADY_CONSUMED);
            assertThat(results).allMatch(result -> result.events().size() >= 1);
            assertThat(planRepository.findAll().stream()
                    .filter(plan -> fixtureKey.equals(plan.getFixtureKey()))).hasSize(1);
            MatchPlan plan = planRepository.findByFixtureKey(fixtureKey).orElseThrow();
            assertThat(plan.getStatus()).isEqualTo(MatchPlan.Status.COMPLETED);
            assertThat(eventRepository.findByFixtureKey(fixtureKey)).isNotEmpty();
            assertThat(eventRepository.findByFixtureKey(fixtureKey)).hasSize(
                    results.get(0).events().size());
            assertThat(appearanceRepository.findByMatchPlan(plan)).isNotEmpty();
            assertThat(predeterminedScores.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                    901L, 7, 3, 11L, 12L).orElseThrow().isConsumed()).isTrue();
        } finally {
            workers.shutdownNow();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                planRepository.findByFixtureKey(fixtureKey).ifPresent(plan -> {
                    eventRepository.findByFixtureKey(fixtureKey).forEach(eventRepository::delete);
                    appearanceRepository.findByMatchPlan(plan).forEach(appearanceRepository::delete);
                    planRepository.delete(plan);
                });
                predeterminedScores.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                        901L, 7, 3, 11L, 12L).ifPresent(predeterminedScores::delete);
                fixtureRepository.deleteById(fixtureId);
            });
        }
    }

    private RunnerResult finishWithRealFixtureLock(MatchScoringDecision decision, String fixtureKey,
                                                    long homeTeamId, long awayTeamId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            matchPlanService.lockFixture(fixtureKey);
            TeamPostMatchService.PredeterminedScoreAttempt claim =
                    postMatchService.consumePredeterminedScoreIfMatches(
                            901L, 7, 3, homeTeamId, awayTeamId,
                            decision.homeScore90(), decision.awayScore90());
            List<MatchEvent> events = matchPlanService.buildAndPersistLive(
                    fixtureKey, 901L, 7, 3, homeTeamId, awayTeamId,
                    lineup(100L), lineup(200L), decision.homeScore90(), decision.awayScore90());
            return new RunnerResult(claim, events);
        });
    }

    private Lineup lineup(long base) {
        return new Lineup(List.of(
                player(base, "GK"), player(base + 1, "DC"), player(base + 2, "DC"),
                player(base + 3, "DL"), player(base + 4, "DR"), player(base + 5, "MC"),
                player(base + 6, "MC"), player(base + 7, "AML"), player(base + 8, "AMR"),
                player(base + 9, "ST"), player(base + 10, "ST")), List.of());
    }

    private Contributor player(long id, String position) {
        return new Contributor(id, "P" + id, position, 15.0,
                15, 15, 15, 100.0, false, false);
    }

    private record RunnerResult(TeamPostMatchService.PredeterminedScoreAttempt claim,
                                List<MatchEvent> events) {}
}
