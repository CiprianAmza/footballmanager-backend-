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
import java.util.Optional;

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
    @Autowired private MarketInstrumentRepository instruments;
    @Autowired private ClubCapTableService capTableService;
    @Autowired private SessionFactory sessionFactory;
    @Autowired private EntityManager entityManager;

    @Test
    void catalogFor106ClubsIsBatchBoundedAndScalarParityHolds() {
        List<Team> fixture = teams.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId)).toList();
        assertThat(fixture).as("bootstrap must provide the 106-club fixture").hasSizeGreaterThanOrEqualTo(106);
        PersonalAccount principal = accounts.findAll().stream()
                .min(java.util.Comparator.comparingLong(PersonalAccount::getId))
                .orElseThrow(() -> new AssertionError("fixture must provide a personal account"));
        java.util.Set<Long> occupiedInstrumentIds = positions.findAll().stream()
                .map(PortfolioPosition::getInstrumentId)
                .collect(java.util.stream.Collectors.toSet());
        MarketInstrument instrument = fixture.stream()
                .map(Team::getId)
                .map(instruments::findByTeamId)
                .flatMap(Optional::stream)
                .filter(value -> value.getInstrumentType() == MarketInstrumentType.CLUB)
                .filter(value -> !occupiedInstrumentIds.contains(value.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture must provide an unoccupied club instrument"));
        assertThat(instrument.getTotalSupply()).isPositive();
        PortfolioPosition seeded = new PortfolioPosition();
        seeded.setAccountId(principal.getId());
        seeded.setProfileId(principal.getProfileId());
        seeded.setInstrumentId(instrument.getId());
        seeded.setQuantity(instrument.getTotalSupply());
        seeded.setTotalCostBasis(Math.multiplyExact(instrument.getTotalSupply(), instrument.getCurrentPrice()));
        positions.saveAndFlush(seeded);
        capTableService.ensureAllMigratedInCurrentTransaction();
        ClubCapTableState state = capTableStates.findByInstrumentId(instrument.getId()).orElseThrow();
        assertThat(state.getControllingAccountId()).isEqualTo(principal.getId());
        assertThat(positions.findByAccountIdAndInstrumentId(principal.getId(), instrument.getId()))
                .get().extracting(PortfolioPosition::getQuantity).isEqualTo(instrument.getTotalSupply());
        long profileId = principal.getProfileId();
        entityManager.flush();
        entityManager.clear();

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
