package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.service.TransferPoints.Offer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransferAffordabilityTest {

    @Test
    @DisplayName("""
            GIVEN a budget of 10,000,000 and a fee of 8,000,000 already agreed for another player
            WHEN I offer 8,000,000 now for a second player, then 2,000,000
            THEN the first is refused (only 2,000,000 is left), the second fits
            """)
    void feesAlreadyAgreedAreHeldBackFromTheBudget() {
        long reserved = TransferAffordability.reserved(List.of(agreed(1, 8_000_000)), 2);
        assertEquals(8_000_000, reserved);
        assertEquals(TransferAffordability.INSUFFICIENT_BUDGET,
                TransferAffordability.refusal(8_000_000, 8_000_000, -1, 10_000_000, reserved));
        assertNull(TransferAffordability.refusal(2_000_000, 2_000_000, -1, 10_000_000, reserved));
    }

    @Test
    @DisplayName("""
            GIVEN the talks with offer 1, whose own fee is the one agreed, and an offer to another club
            WHEN the held-back money is counted for offer 1
            THEN its own fee does not count, and only fee agreements of my outgoing bids count
            """)
    void anOffersOwnFeeAndOtherKindsAreNotHeldBack() {
        TransferOffer incoming = agreed(3, 5_000_000);
        incoming.setDirection("incoming");
        TransferOffer talks = agreed(4, 7_000_000);
        talks.setStatus(TransferOffer.STATUS_TALKS);
        assertEquals(0, TransferAffordability.reserved(List.of(agreed(1, 8_000_000), incoming, talks), 1));
    }

    @Test
    @DisplayName("""
            GIVEN a chairman's cap of 1,000,000
            WHEN I offer 0 now + 3 × 3,750,000, or 1 now + a 60-goal bonus of 1,000,000,000
            THEN both are over the cap: it limits the whole fee, not just the cash paid now
            """)
    void theCapCoversInstalmentsAndBonuses() {
        Offer instalments = new Offer(0, 3, 3_750_000, 0, 0);
        Offer bonus = new Offer(1, 0, 0, 0, 0, 60, 1_000_000_000L, 0);
        assertEquals(11_250_000, TransferAffordability.wholeFee(instalments));
        assertEquals(TransferAffordability.OVER_CAP,
                TransferAffordability.refusal(0, TransferAffordability.wholeFee(instalments), 1_000_000, 50_000_000, 0));
        assertEquals(TransferAffordability.OVER_CAP,
                TransferAffordability.refusal(1, TransferAffordability.wholeFee(bonus), 1_000_000, 50_000_000, 0));
    }

    @Test
    @DisplayName("""
            GIVEN no cap and a budget of 1,000,000
            WHEN I offer 0 now + 2 × 2,000,000 (paid in later seasons), a negative cash part, or nothing at all
            THEN the instalments fit (the budget pays the cash now); the other two are invalid
            """)
    void theBudgetPaysTheCashNow() {
        assertNull(TransferAffordability.refusal(0, 4_000_000, -1, 1_000_000, 0));
        assertEquals(TransferAffordability.INVALID_AMOUNT, TransferAffordability.refusal(-1, 4_000_000, -1, 1_000_000, 0));
        assertEquals(TransferAffordability.INVALID_AMOUNT, TransferAffordability.refusal(0, 0, -1, 1_000_000, 0));
    }

    private static TransferOffer agreed(long id, long amount) {
        TransferOffer offer = new TransferOffer();
        offer.setId(id);
        offer.setDirection("outgoing");
        offer.setStatus(TransferOffer.STATUS_FEE_AGREED);
        offer.setOfferAmount(amount);
        return offer;
    }
}
