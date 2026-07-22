package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that two requests for one real fixture serialize and both reuse one plan. */
@DataJpaTest
@Import({MatchPlanService.class, MatchPlanningService.class, InstantMatchExecutor.class,
        ContributionResolver.class, MatchEngineConfig.class})
class MatchPlanConcurrencyTest {

    @Autowired private MatchPlanService service;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository participantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private LineupAdapter lineupAdapter;

    @Test
    void concurrentCalls_sameFixtureBothSucceedWithoutDuplicates() throws Exception {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(100L);
        fixture.setSeasonNumber("1");
        fixture.setRound(5L);
        fixture.setTeam1Id(10L);
        fixture.setTeam2Id(20L);
        fixture = fixtureRepository.saveAndFlush(fixture);
        long fixtureId = fixture.getId();
        String fixtureKey = MatchPlanService.competitionFixtureKey(fixtureId);

        // Distinct player ids per team (a player belongs to one team per match).
        when(lineupAdapter.build(eq(10L), any(), anyLong())).thenReturn(lineup(100L));
        when(lineupAdapter.build(eq(20L), any(), anyLong())).thenReturn(lineup(200L));

        // Commit the fixture before worker transactions attempt SELECT ... FOR UPDATE.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<MatchEvent>> first = executor.submit(() -> {
                start.await();
                return service.buildAndPersist(fixtureKey, 100L, 1, 5,
                        10L, 20L, "4-4-2", "4-4-2", 2, 1);
            });
            Future<List<MatchEvent>> second = executor.submit(() -> {
                start.await();
                return service.buildAndPersist(fixtureKey, 100L, 1, 5,
                        10L, 20L, "4-4-2", "4-4-2", 2, 1);
            });

            start.countDown();
            List<MatchEvent> firstResult = first.get(10, TimeUnit.SECONDS);
            List<MatchEvent> secondResult = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstResult.size(), secondResult.size());
            assertEquals(1, planRepository.findAll().stream()
                    .filter(p -> fixtureKey.equals(p.getFixtureKey())).count());
            assertEquals(firstResult.size(), eventRepository.findByFixtureKey(fixtureKey).size());
            // Only the winning transaction builds the two lineups.
            verify(lineupAdapter, times(2)).build(anyLong(), any(), anyLong());
        } finally {
            executor.shutdownNow();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                eventRepository.findByFixtureKey(fixtureKey).forEach(eventRepository::delete);
                planRepository.findByFixtureKey(fixtureKey).ifPresent(plan -> {
                    appearanceRepository.findByMatchPlan(plan).forEach(appearanceRepository::delete);
                    participantRepository.findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan).forEach(participantRepository::delete);
                    substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan)
                            .forEach(substitutionRepository::delete);
                    planRepository.delete(plan);
                });
                fixtureRepository.deleteById(fixtureId);
            });
        }
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
}
