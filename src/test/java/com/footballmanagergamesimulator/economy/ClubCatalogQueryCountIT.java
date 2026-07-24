package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired private SessionFactory sessionFactory;

    @Test
    void catalogFor106ClubsIsBatchBoundedAndScalarParityHolds() {
        List<Team> fixture = teams.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId)).toList();
        assertThat(fixture).as("bootstrap must provide the 106-club fixture").hasSizeGreaterThanOrEqualTo(106);
        long profileId = accounts.findAll().stream().findFirst().map(PersonalAccount::getProfileId).orElse(0L);

        sessionFactory.getStatistics().clear();
        catalog.clubs(ClubCatalogScope.ALL, profileId);
        assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isLessThanOrEqualTo(20);

        catalog.clubs(ClubCatalogScope.HELD, profileId);
        catalog.clubs(ClubCatalogScope.CONTROLLED, profileId);

        List<Team> representatives = fixture.subList(0, Math.min(3, fixture.size()));
        Map<Long, ClubValuationService.Valuation> batch = valuations.valueBatch(representatives);
        for (Team team : representatives) {
            ClubValuationService.Valuation scalar = valuations.value(team.getId());
            assertThat(batch.get(team.getId())).usingRecursiveComparison().isEqualTo(scalar);
        }
    }
}
