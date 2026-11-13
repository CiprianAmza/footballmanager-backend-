package com.footballmanagergamesimulator.service;

import java.util.Random;

/**
 * The selling club's price (design §3.5, decided 2026-11-12). Pure rules.
 * <ul>
 *   <li><b>Real price</b> = the market value (rating, age) × contract left × wants to leave × importance in the
 *       squad × club in trouble × interest of other clubs, never above the release clause.</li>
 *   <li><b>What the club wants</b> = the real price moved at random by −35%…+40%, fixed when the talks open and
 *       hidden from the buyer until his first offer. Still never above the release clause.</li>
 * </ul>
 */
public final class TransferPricing {

    public static final double MIN_WANT = 0.65;
    public static final double MAX_WANT = 1.40;
    public static final double OTHER_OFFER_PREMIUM = 0.10;
    public static final int MAX_OTHER_OFFERS_COUNTED = 3;
    public static final int STARTERS = 11;
    public static final int ROTATION = 18;

    /** What is known about the player and his club when the price is set. */
    public record Context(long marketValue, int contractYearsLeft, boolean wantsToLeave, int squadRank,
                          boolean clubInTrouble, int otherActiveOffers, long releaseClause) {
    }

    private TransferPricing() {
    }

    public static double contractFactor(int yearsLeft) {
        if (yearsLeft >= 3) return 1.0;
        if (yearsLeft == 2) return 0.9;
        if (yearsLeft == 1) return 0.75;
        return 0.5;
    }

    /** 1 = best-rated player of the club. Starters (top 11) cost more, reserves (beyond 18) less. */
    public static double importanceFactor(int squadRank) {
        if (squadRank <= STARTERS) return 1.2;
        if (squadRank <= ROTATION) return 1.0;
        return 0.8;
    }

    public static long realPrice(Context c) {
        double price = c.marketValue()
                * contractFactor(c.contractYearsLeft())
                * (c.wantsToLeave() ? 0.7 : 1.0)
                * importanceFactor(c.squadRank())
                * (c.clubInTrouble() ? 0.8 : 1.0)
                * (1 + OTHER_OFFER_PREMIUM * Math.min(MAX_OTHER_OFFERS_COUNTED, Math.max(0, c.otherActiveOffers())));
        return capByClause(Math.round(price), c.releaseClause());
    }

    /** The club's hidden price: the real price × a random factor in [0.65, 1.40]. */
    public static long clubWants(long realPrice, long releaseClause, Random rng) {
        double factor = MIN_WANT + (MAX_WANT - MIN_WANT) * rng.nextDouble();
        return capByClause(Math.max(1, Math.round(realPrice * factor)), releaseClause);
    }

    private static long capByClause(long price, long releaseClause) {
        return releaseClause > 0 ? Math.min(price, releaseClause) : price;
    }
}
