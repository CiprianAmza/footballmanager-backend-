package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.LeaguePrizePoolConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrizeMoneyOverviewServiceTest {

    @Test
    void positionDistributionPaysTheExactPoolAndRewardsEveryHigherPosition() {
        LeaguePrizePoolConfig config = new LeaguePrizePoolConfig();
        PrizeMoneyOverviewService service = new PrizeMoneyOverviewService(
                null, null, null, null, null, null, config, null, null);

        List<PrizeMoneyOverviewService.PositionPrize> rows = service.positionDistribution(1_000_000_003L, 14);

        assertEquals(14, rows.size());
        assertEquals(1_000_000_003L, rows.stream().mapToLong(PrizeMoneyOverviewService.PositionPrize::amount).sum());
        for (int index = 1; index < rows.size(); index++) {
            assertTrue(rows.get(index - 1).amount() > rows.get(index).amount());
        }
        assertTrue(rows.get(0).amount() >= rows.get(rows.size() - 1).amount() * 4.9);
    }
}
