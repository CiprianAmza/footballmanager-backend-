package com.footballmanagergamesimulator.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The extra growth of a good season, for one league and one statistics category.
 *
 * <p>The league's top 10 at the statistic is ranked; only the first three grow, each by a
 * share of the average rating of the players below him in that top 10 — 15% for 1st, 10% for
 * 2nd, 5% for 3rd. The growth depends on the level of the players he beat, not on his own
 * rating. Pure: the caller supplies the statistic and ratings and applies the growth.
 */
public final class SeasonStatGrowth {

    public static final int TOP = 10;
    static final double[] SHARE_BY_PLACE = {0.15, 0.10, 0.05};

    /** A player who grows: his place (1-3) and by how much his rating should rise. */
    public record Award(long playerId, int place, double ratingGrowth) {}

    private SeasonStatGrowth() {
    }

    /**
     * @param statByPlayer   the statistic of each player in the league (players at 0 do not count)
     * @param ratingByPlayer each player's rating before any growth of this season
     */
    public static List<Award> awards(Map<Long, Double> statByPlayer, Map<Long, Double> ratingByPlayer) {
        List<Long> top = statByPlayer.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .filter(entry -> ratingByPlayer.containsKey(entry.getKey()))
                .sorted(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP)
                .map(Map.Entry::getKey)
                .toList();

        List<Award> awards = new ArrayList<>();
        for (int place = 0; place < SHARE_BY_PLACE.length && place < top.size(); place++) {
            List<Long> below = top.subList(place + 1, top.size());
            if (below.isEmpty()) break;
            double reference = below.stream().mapToDouble(ratingByPlayer::get).average().orElse(0);
            awards.add(new Award(top.get(place), place + 1, SHARE_BY_PLACE[place] * reference));
        }
        return awards;
    }
}
