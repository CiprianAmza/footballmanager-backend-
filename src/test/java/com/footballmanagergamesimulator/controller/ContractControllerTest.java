package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractControllerTest {

    private final ContractController controller = new ContractController();

    @Test
    void renewalDemandAlwaysStaysWithinFortyPercentOfCurrentWage() {
        Human star = player(100_000, 20, 40, 30, true, 105);
        Human veteranReserve = player(100_000, 36, 0, 100, false, 70);

        long starDemand = controller.calculateWageDemand(star);
        long reserveDemand = controller.calculateWageDemand(veteranReserve);

        assertTrue(starDemand >= 60_000 && starDemand <= 140_000);
        assertTrue(reserveDemand >= 60_000 && reserveDemand <= 140_000);
    }

    @Test
    void regularHappyPlayerGetsARequestCloseToCurrentWage() {
        Human player = player(100_000, 27, 15, 90, false, 80);

        assertEquals(102_000, controller.calculateWageDemand(player));
    }

    private Human player(long wage, int age, int matches, double morale,
                         boolean wantsTransfer, double rating) {
        Human player = new Human();
        player.setWage(wage);
        player.setAge(age);
        player.setSeasonMatchesPlayed(matches);
        player.setMorale(morale);
        player.setWantsTransfer(wantsTransfer);
        player.setRating(rating);
        player.setPotentialAbility((int) Math.round(rating + 15));
        return player;
    }
}
