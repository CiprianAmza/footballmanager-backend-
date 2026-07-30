package com.footballmanagergamesimulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * One pot of prize money for the whole game, shared between championships by how
 * strong they are — the broadcast-rights model, where money follows interest.
 *
 * <p>Replaces a fixed ladder of per-rank amounts. A ladder cannot say that two
 * championships are worth the same: it hands out 1000M and 800M to divisions
 * separated by two rating points, then 600M and 500M to divisions separated by
 * less than one. Rank is a lie whenever the values behind it are level. Sharing
 * the pot by strength pays equals equally and turns real gaps into real money.
 *
 * <p><b>Why the exponent.</b> Ratings have no natural zero, so a raw proportional
 * split is nearly flat: measured league averages span roughly 98 to 191, a ratio
 * of under 2:1, which would leave the weakest division in the game on almost half
 * of what the strongest earns. Weighting by {@code strength^exponent} restores a
 * meaningful hierarchy while keeping level divisions level. Measured on live data,
 * exponent 3 gives about 7:1 between best and worst, and 4 gives about 14:1.
 * This is the single dial for how unequal the world is.
 *
 * <p><b>Why a fixed total.</b> Adding a championship dilutes the existing ones
 * instead of minting new money, so the economy does not inflate every time the
 * world grows.
 */
@Data
@Component
@ConfigurationProperties(prefix = "finance.league-prize")
public class LeaguePrizePoolConfig {

    /** Total season prize money shared by every top-flight championship. */
    private long totalPool = 5_000_000_000L;

    /** Steepness of the value-to-money curve. 1 = raw proportional (nearly flat). */
    private double strengthExponent = 4.0;

    /**
     * Extra weight for finishing top of the strength table, on top of the share the
     * division's quality already earns. Keys are inclusive rank ceilings, matching
     * the convention in {@link AwardWeightingConfig}. Being the best-regarded league
     * is worth something by itself; it should not be worth much, or it re-creates
     * the ladder this replaced.
     */
    private Map<Integer, Double> rankBonuses = new TreeMap<>(Map.of(
            1, 1.15,
            2, 1.07));

    private double defaultRankBonus = 1.0;

    /**
     * What a second tier earns, as a fraction of its OWN country's top-flight pool.
     *
     * <p>Not a share of a pot reserved for second tiers: only one second division
     * exists today, so such a pot would go to it entire. Anchoring to the parent
     * league keeps a reserve division proportionate to the country it belongs to,
     * and guarantees a top flight always out-earns its own second tier — which
     * ranking the two together by squad quality would not.
     */
    private double secondLeagueFraction = 0.10;

    /**
     * How much of a division's pool the final table strips away between first and
     * last. 0.8 leaves the bottom club on 20% of the champion's share.
     */
    private double positionSpread = 0.8;

    public double rankBonus(int rank) {
        return rankBonuses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> rank <= entry.getKey())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultRankBonus);
    }
}
