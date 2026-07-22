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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 4: the live lifecycle mutations lock the fixture, so two concurrent requests
 * cannot resolve one slot twice or allocate the same substitution index.
 */
@DataJpaTest
@Import({MatchPlanService.class, MatchPlanningService.class, InstantMatchExecutor.class,
        ContributionResolver.class, MatchEngineConfig.class})
class MatchPlanLiveConcurrencyTest {

    @Autowired private MatchPlanService service;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository participantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private LineupAdapter lineupAdapter;
    @MockBean private com.footballmanagergamesimulator.user.UserContext userContext;

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private List<Contributor> xi(long base) {
        return List.of(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "MC"), p(base + 4, "MC"),
                p(base + 5, "AMR"), p(base + 6, "AML"), p(base + 7, "ST"), p(base + 8, "ST"),
                p(base + 9, "DL"), p(base + 10, "DR"), p(base + 11, "DC"));
    }

    private String seedPreparedPlan(int homeScore) {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(100L);
        fixture.setSeasonNumber("1");
        fixture.setRound(5L);
        fixture.setTeam1Id(10L);
        fixture.setTeam2Id(20L);
        fixture = fixtureRepository.saveAndFlush(fixture);
        String fx = MatchPlanService.competitionFixtureKey(fixture.getId());
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L,
                new Lineup(xi(100), List.of(p(199, "ST")), List.of()),
                new Lineup(xi(200), List.of(), List.of()), homeScore, 0);
        // Commit so the worker transactions (SELECT ... FOR UPDATE) observe it.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        return fx;
    }

    @Test
    void concurrentResolveOfSameSlot_resolvesExactlyOnce() throws Exception {
        String fx = seedPreparedPlan(1); // one home slot, slotIndex 0
        List<Contributor> onPitch = xi(100);

        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var f1 = ex.submit(() -> { start.await(); return service.resolveDueSlot(fx, 100L, 1, 5, 0, onPitch); });
            var f2 = ex.submit(() -> { start.await(); return service.resolveDueSlot(fx, 100L, 1, 5, 0, onPitch); });
            start.countDown();
            List<MatchEvent> r1 = f1.get(10, TimeUnit.SECONDS);
            List<MatchEvent> r2 = f2.get(10, TimeUnit.SECONDS);

            long goals = eventRepository.findByFixtureKey(fx).stream()
                    .filter(e -> "goal".equals(e.getEventType())).count();
            assertEquals(1, goals, "the slot is resolved exactly once, no duplicate goal");
            assertEquals(scorerOf(r1), scorerOf(r2), "both requests observe the same scorer");
        } finally {
            ex.shutdownNow();
            cleanup(fx);
        }
    }

    @Test
    void concurrentSubstitutions_getDistinctSubIndex() throws Exception {
        String fx = seedPreparedPlan(0); // 0-0, no goal slots to resolve

        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var f1 = ex.submit(() -> { start.await(); service.recordLiveSubstitution(fx, 10L, 60, 102L, 199L); return null; });
            var f2 = ex.submit(() -> { start.await(); service.recordLiveSubstitution(fx, 10L, 70, 103L, 198L); return null; });
            start.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);

            MatchPlan plan = planRepository.findByFixtureKey(fx).orElseThrow();
            List<Integer> indices = substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                    .filter(s -> s.getTeamId() == 10L).map(MatchSubstitution::getSubIndex).sorted().toList();
            assertEquals(List.of(0, 1), indices, "two concurrent subs get distinct consecutive indices");
        } finally {
            ex.shutdownNow();
            cleanup(fx);
        }
    }

    private long scorerOf(List<MatchEvent> events) {
        return events.stream().filter(e -> "goal".equals(e.getEventType()))
                .mapToLong(MatchEvent::getPlayerId).findFirst().orElseThrow();
    }

    private void cleanup(String fx) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            eventRepository.findByFixtureKey(fx).forEach(eventRepository::delete);
            planRepository.findByFixtureKey(fx).ifPresent(plan -> {
                appearanceRepository.findByMatchPlan(plan).forEach(appearanceRepository::delete);
                substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).forEach(substitutionRepository::delete);
                participantRepository.findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan).forEach(participantRepository::delete);
                planRepository.delete(plan);
            });
        });
    }
}
