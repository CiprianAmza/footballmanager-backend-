package com.footballmanagergamesimulator.service;

/**
 * How the parent club judges a loan offer (design §3.5, decided 2026-11-12).
 *
 * <ul>
 *   <li>Plain loan: the fee is 5–10% of his value and he is not among the club's top 15.</li>
 *   <li>With an option to buy: the same, and the option price is at least his real price (the club loses control
 *       of the sale).</li>
 *   <li>With an obligation to buy: a sale paid later. The loan fee plus the obligatory price, weighted like
 *       instalments (money a season away), must reach his real price; the top-15 rule does not apply.</li>
 * </ul>
 */
public final class LoanTerms {

    public static final double MIN_FEE_SHARE = 0.05;
    public static final double MAX_FEE_SHARE = 0.10;
    public static final int PROTECTED_SQUAD_PLACES = 15;

    private LoanTerms() {
    }

    /** Null when the club accepts; otherwise its reason. */
    public static String refusal(long loanFee, long transferValue, boolean inTop15, long buyPrice, boolean obligatory,
                                 long realPrice, TransferPoints.Situation situation) {
        if (loanFee < 0) return "The loan fee cannot be negative.";
        if (obligatory) {
            if (buyPrice <= 0) return "An obligation to buy needs a purchase price.";
            long points = loanFee + Math.round(situation.instalmentWeight * buyPrice);
            return points >= realPrice ? null
                    : "The loan fee and the obligatory price are worth too little to the club for a player it values at "
                    + realPrice + ".";
        }
        if (inTop15) return "The club considers this player too important to loan out (top 15 in squad).";
        long minFee = Math.max(1, (long) (transferValue * MIN_FEE_SHARE));
        long maxFee = (long) (transferValue * MAX_FEE_SHARE);
        if (loanFee < minFee) return "Loan fee too low. Minimum required: " + minFee + " (5% of value).";
        if (loanFee > maxFee) return "Loan fee too high. Maximum acceptable: " + maxFee + " (10% of value).";
        if (buyPrice > 0 && buyPrice < realPrice) {
            return "The option price is too low: the club will not let him go for less than " + realPrice + ".";
        }
        return null;
    }
}
