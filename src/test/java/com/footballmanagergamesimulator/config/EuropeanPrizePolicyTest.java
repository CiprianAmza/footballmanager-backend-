package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.model.Competition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EuropeanPrizePolicyTest {

    private final EuropeanPrizePolicy policy = new EuropeanPrizePolicy();

    @Test
    void keepsLeagueOfChampionsAwardsInOnePolicy() {
        assertEquals(20_000_000L, policy.groupParticipation(Competition.LEAGUE_OF_CHAMPIONS));
        assertEquals(5_000_000L, policy.groupWin(Competition.LEAGUE_OF_CHAMPIONS));
        assertEquals(15_000_000L, policy.knockoutFixtureBonus(Competition.LEAGUE_OF_CHAMPIONS, 3));
        assertEquals(40_000_000L, policy.knockoutFixtureBonus(Competition.LEAGUE_OF_CHAMPIONS, 2));
        assertEquals(100_000_000L, policy.winnerPrize(Competition.LEAGUE_OF_CHAMPIONS));
    }

    @Test
    void keepsStarsCupAwardsSeparate() {
        assertEquals(5_000_000L, policy.groupParticipation(Competition.STARS_CUP));
        assertEquals(1_500_000L, policy.groupWin(Competition.STARS_CUP));
        assertEquals(5_000_000L, policy.knockoutFixtureBonus(Competition.STARS_CUP, 3));
        assertEquals(15_000_000L, policy.winnerPrize(Competition.STARS_CUP));
        assertEquals(8_000_000L, policy.runnerUpPrize(Competition.STARS_CUP));
    }
}
