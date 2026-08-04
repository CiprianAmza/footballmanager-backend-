package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.DefensivePressure;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.DefensivePressureRepository;
import com.footballmanagergamesimulator.repository.PossessionProgressionRepository;
import com.footballmanagergamesimulator.repository.ShotEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefensivePressureLedgerServiceTest {
    private final DefensivePressureLedgerService service = new DefensivePressureLedgerService(
            mock(DefensivePressureRepository.class),
            new PossessionProgressionLedgerService(mock(PossessionProgressionRepository.class)),
            new ShotEventService(mock(ShotEventRepository.class)));

    @Test
    void createsTwoConsistentRowsWithoutClaimingObservedTracking() {
        MatchStats match = PossessionProgressionLedgerServiceTest.match();
        match.setHomeTackles(20); match.setAwayTackles(15); match.setHomeInterceptions(11); match.setAwayInterceptions(8);
        match.setHomeFouls(10); match.setAwayFouls(13); match.setHomeClearances(14); match.setAwayClearances(22);
        match.setHomeDuelsWon(31); match.setAwayDuelsWon(24); match.setHomeShotsBlocked(3); match.setAwayShotsBlocked(4);
        match.setHomeGoals(2); match.setAwayGoals(1); match.setHomeShotsOnTarget(6); match.setAwayShotsOnTarget(3);

        List<DefensivePressure> first = service.generate(match);
        List<DefensivePressure> second = service.generate(match);
        DefensivePressure home = first.stream().filter(row -> row.getTeamId() == 10).findFirst().orElseThrow();

        assertThat(first).hasSize(2);
        assertThat(second).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").containsExactlyElementsOf(first);
        assertThat(home.getSuccessfulPressures()).isLessThanOrEqualTo(home.getPressures());
        assertThat(home.getRegainsWithinFiveSeconds()).isLessThanOrEqualTo(home.getRegainsWithinEightSeconds());
        assertThat(home.getRegainsWithinEightSeconds()).isLessThanOrEqualTo(home.getCounterpressures());
        assertThat(home.getHighTurnoversToShot()).isLessThanOrEqualTo(home.getHighTurnovers());
        assertThat(home.getDuels()).isEqualTo(55);
        assertThat(home.getBlocks()).isEqualTo(4);
        assertThat(home.getDataQuality()).isEqualTo("MODELED_FROM_MATCH_STATS");
    }
}
