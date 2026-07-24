package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Executable acceptance test for the bounded club catalog read model. */
@SpringBootTest
@Transactional
class ClubCatalogQueryCountIT {
    @Autowired private ClubQueryService catalog;
    @Autowired private ClubValuationService valuations;
    @Autowired private TeamRepository teams;
    @Autowired private PersonalAccountRepository accounts;
    @Autowired private PortfolioPositionRepository positions;
    @Autowired private ClubCapTableStateRepository capTableStates;
    @Autowired private SessionFactory sessionFactory;
    @Autowired private EntityManager entityManager;

    @Test
    void catalogFor106ClubsIsBatchBoundedAndScalarParityHolds() {
        List<Team> fixture = teams.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId)).toList();
        assertThat(fixture).as("bootstrap must provide the 106-club fixture").hasSizeGreaterThanOrEqualTo(106);
        Map<Long, PersonalAccount> accountsById = accounts.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(PersonalAccount::getId, account -> account));
        List<PortfolioPosition> positiveHoldings = positions.findAllByQuantityGreaterThan(0);
        long profileId = capTableStates.findAll().stream()
                .filter(state -> state.getControllingAccountId() != null)
                .filter(state -> positiveHoldings.stream().anyMatch(position ->
                        position.getAccountId() == state.getControllingAccountId()
                                && position.getInstrumentId() == state.getInstrumentId()))
                .map(state -> accountsById.get(state.getControllingAccountId()))
                .filter(java.util.Objects::nonNull)
                .map(PersonalAccount::getProfileId)
                .sorted().findFirst()
                .orElseThrow(() -> new AssertionError("fixture must provide a profile with a holding and controlling holding"));

        Statistics statistics = sessionFactory.getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            assertBoundedCatalogRead(ClubCatalogScope.ALL, profileId, statistics);
            List<ClubDtos.ClubSummary> held = assertBoundedCatalogRead(ClubCatalogScope.HELD, profileId, statistics);
            List<ClubDtos.ClubSummary> controlled = assertBoundedCatalogRead(ClubCatalogScope.CONTROLLED, profileId, statistics);
            assertThat(held).isNotNull().isNotEmpty();
            assertThat(controlled).isNotNull().isNotEmpty();
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }

        List<Team> representatives = fixture.subList(0, Math.min(3, fixture.size()));
        Map<Long, ClubValuationService.Valuation> batch = valuations.valueBatch(representatives);
        for (Team team : representatives) {
            ClubValuationService.Valuation scalar = valuations.value(team.getId());
            assertThat(batch.get(team.getId())).usingRecursiveComparison().isEqualTo(scalar);
        }
    }

    private List<ClubDtos.ClubSummary> assertBoundedCatalogRead(ClubCatalogScope scope, long profileId, Statistics statistics) {
        entityManager.clear();
        statistics.clear();
        List<ClubDtos.ClubSummary> result = catalog.clubs(scope, profileId);
        long statements = statistics.getPrepareStatementCount();
        assertThat(statements).as(scope + " catalog statements").isGreaterThan(0).isLessThanOrEqualTo(20);
        return result;
    }
}
