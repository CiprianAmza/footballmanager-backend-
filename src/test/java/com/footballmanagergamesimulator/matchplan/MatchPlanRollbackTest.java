package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real transactional rollback: {@link MatchPlanService} is a Spring-proxied bean
 * so its {@code @Transactional} is active. When event persistence fails, the plan
 * insert in the same transaction must be rolled back — no plan left behind.
 */
@DataJpaTest
@Import({MatchPlanService.class, MatchPlanningService.class, InstantMatchExecutor.class,
        ContributionResolver.class, MatchEngineConfig.class})
class MatchPlanRollbackTest {

    @Autowired private MatchPlanService service;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private LineupAdapter lineupAdapter;
    @MockBean private MatchEventRepository matchEventRepository;
    @MockBean private CompetitionTeamInfoMatchRepository fixtureRepository;

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    @BeforeEach
    void setup() {
        Lineup xi = new Lineup(List.of(
                p(1, "GK"), p(2, "DC"), p(3, "MC"), p(4, "AMR"), p(5, "ST"), p(6, "ST"),
                p(7, "AML"), p(8, "MC"), p(9, "DL"), p(10, "DR"), p(11, "DC")), List.of());
        when(lineupAdapter.build(anyLong(), any(), anyLong())).thenReturn(xi);
        when(fixtureRepository.findByIdForUpdate(anyLong()))
                .thenReturn(java.util.Optional.of(new CompetitionTeamInfoMatch()));
        when(matchEventRepository.saveAll(any())).thenThrow(new RuntimeException("event persist failed"));
    }

    @Test
    void eventPersistFailure_rollsBackPlan() {
        // Leave the test-managed transaction, then wrap the call in a real
        // transaction so the plan save and the failing event save share one unit
        // of work — the failure must roll the plan back too.
        TestTransaction.end();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status ->
                service.buildAndPersist("CTIM:500", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 2, 1)));

        assertEquals(0, planRepository.count(), "plan must not survive a failed event persist");
    }
}
