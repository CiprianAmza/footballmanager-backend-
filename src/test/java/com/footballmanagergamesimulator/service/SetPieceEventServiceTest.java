package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.SetPieceEvent;
import com.footballmanagergamesimulator.repository.SetPieceEventRepository;
import com.footballmanagergamesimulator.repository.ShotEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SetPieceEventServiceTest {
    private final SetPieceEventService service = new SetPieceEventService(mock(SetPieceEventRepository.class),
            new ShotEventService(mock(ShotEventRepository.class)));

    @Test
    void preservesObservedCountsAndGeneratesDeterministicDeliveryDetails() {
        MatchStats match = match();
        List<SetPieceEvent> first = service.generate(match);
        List<SetPieceEvent> second = service.generate(match);
        List<SetPieceEvent> home = first.stream().filter(row -> row.getTeamId() == 10).toList();

        assertThat(home).filteredOn(row -> "CORNER".equals(row.getType())).hasSize(7);
        assertThat(home).filteredOn(row -> "PENALTY".equals(row.getType())).hasSizeLessThanOrEqualTo(1);
        assertThat(home).allSatisfy(row -> {
            assertThat(row.getDeliveryStyle()).isNotBlank(); assertThat(row.getDeliveryZone()).isNotBlank();
            assertThat(row.getFirstContact()).isNotBlank(); assertThat(row.getSecondBallRecovery()).isNotBlank();
            assertThat(row.getXg()).isNotNegative(); assertThat(row.getDataQuality()).isEqualTo("MODELED_DELIVERY_FROM_MATCH_STATS");
        });
        assertThat(second).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").containsExactlyElementsOf(first);
    }

    static MatchStats match() {
        MatchStats match = PossessionProgressionLedgerServiceTest.match();
        match.setHomeGoals(2); match.setAwayGoals(1); match.setHomeShotsOnTarget(6); match.setAwayShotsOnTarget(3);
        match.setHomeShotsBlocked(3); match.setAwayShotsBlocked(2); match.setHomeBigChances(3); match.setAwayBigChances(1);
        match.setHomeXg(180); match.setAwayXg(80); match.setHomeFreeKicks(12); match.setAwayFreeKicks(9);
        return match;
    }
}
