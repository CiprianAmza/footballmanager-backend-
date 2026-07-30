package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Team;

import java.util.List;
import java.util.Map;
import java.util.Random;

public interface TransferStrategy {

    /**
     * Which squad members this club puts up for sale, drawn from the starters /
     * reserves split in the depth chart.
     */
    List<PlayerTransferView> playersToSell(Team team,
                                           SquadDepthChart depthChart,
                                           Map<String, Integer> minimumPositionNeeded);

    /**
     * Which positions this club wants to strengthen, ranked by how far each drags
     * its XI down. {@code null} means the club sits the window out.
     */
    BuyPlanTransferView playersToBuy(Team team,
                                     SquadDepthChart depthChart,
                                     Map<String, Integer> maximumPositionsAllowed);

    /**
     * Test-only seam: swap the RNG so fuzz/integration tests get reproducible
     * sell counts + buy-plan shuffles. Production never calls this — the RNG
     * stays the default non-seeded {@link Random}. Default no-op so the
     * composite (which has no RNG of its own) need not implement it.
     */
    default void setRandom(Random random) { }
}
