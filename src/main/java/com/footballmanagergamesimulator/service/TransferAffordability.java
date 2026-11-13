package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TransferOffer;

import java.util.Collection;

/**
 * Whether a club can make a transfer offer.
 *
 * <ul>
 *   <li>The chairman's cap limits the whole fee: cash now, instalments and performance bonuses, at their full
 *       amount. Otherwise "0 now + instalments" or "1 now + a huge bonus" would get round it.</li>
 *   <li>The budget pays the cash now. Fees already agreed (waiting for personal terms) are held back from it, so
 *       two agreements cannot spend the same money.</li>
 * </ul>
 */
public final class TransferAffordability {

    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String OVER_CAP = "OVER_CAP";
    public static final String INSUFFICIENT_BUDGET = "INSUFFICIENT_BUDGET";

    private TransferAffordability() {
    }

    /**
     * Null when the offer is affordable; otherwise the reason code.
     *
     * @param cashNow         money paid when the transfer completes
     * @param wholeFee        cash now + instalments + bonuses
     * @param cap             the chairman's cap on a transfer, negative for none
     * @param budget          the club's transfer budget
     * @param reserved        fees the club already agreed and has not paid yet
     */
    public static String refusal(long cashNow, long wholeFee, long cap, long budget, long reserved) {
        if (cashNow < 0 || wholeFee <= 0) return INVALID_AMOUNT;
        if (cap >= 0 && wholeFee > cap) return OVER_CAP;
        if (cashNow > budget - reserved) return INSUFFICIENT_BUDGET;
        return null;
    }

    /** Cash now plus everything that may be paid later, for the cap. */
    public static long wholeFee(TransferPoints.Offer offer) {
        return offer.total() + offer.goalsBonusAmount() + offer.titleBonusAmount();
    }

    /** The cash of the club's fee agreements still waiting for personal terms, except {@code exceptOfferId}. */
    public static long reserved(Collection<TransferOffer> feeAgreed, long exceptOfferId) {
        return feeAgreed.stream()
                .filter(o -> o.getId() != exceptOfferId && "outgoing".equalsIgnoreCase(o.getDirection())
                        && TransferOffer.STATUS_FEE_AGREED.equals(o.getStatus()))
                .mapToLong(TransferOffer::getOfferAmount)
                .sum();
    }
}
