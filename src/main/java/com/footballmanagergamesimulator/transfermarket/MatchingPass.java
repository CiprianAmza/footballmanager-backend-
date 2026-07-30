package com.footballmanagergamesimulator.transfermarket;

/**
 * The market runs twice per window. The first pass is the clubs' actual intent;
 * the second is a clearance round where everything unsold is re-offered on softer
 * terms, so a club that failed to trade is not simply locked out for a season.
 *
 * <p>Both passes run the identical matching function — only these numbers change,
 * which is why relaxing the market cannot drift away from the primary rules.
 */
public enum MatchingPass {

    /** Clubs get what they actually want. */
    PRIMARY(0D, 1.0D),

    /**
     * Clearance: bars drop by {@link #ratingRelaxation()} rating points and fees are
     * discounted — a listed player the club wanted rid of is not worth full market
     * price, and the alternative is he does not move at all.
     */
    CLEARANCE(35D, 0.6D);

    private final double ratingRelaxation;
    private final double feeMultiplier;

    MatchingPass(double ratingRelaxation, double feeMultiplier) {
        this.ratingRelaxation = ratingRelaxation;
        this.feeMultiplier = feeMultiplier;
    }

    /** How far below the normal bar a candidate may fall in this pass. */
    public double ratingRelaxation() {
        return ratingRelaxation;
    }

    public double feeMultiplier() {
        return feeMultiplier;
    }

    public long applyDiscount(long fee) {
        return (long) (fee * feeMultiplier);
    }
}
