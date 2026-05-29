package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

public interface TransferStrategy {

    List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded);

    BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maximumPositionsAllowed);

    /**
     * Test-only seam: swap the RNG so fuzz/integration tests get reproducible
     * sell counts + buy-plan shuffles. Production never calls this — the RNG
     * stays the default non-seeded {@link Random}. Default no-op so the
     * composite (which has no RNG of its own) need not implement it.
     */
    default void setRandom(Random random) { }
}
