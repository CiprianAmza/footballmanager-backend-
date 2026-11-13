package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoanTermsTest {

    private static final TransferPoints.Situation STABLE = TransferPoints.Situation.STABLE;

    @Test
    @DisplayName("""
            GIVEN a player valued 1,000,000 with a real price of 1,200,000, outside the club's top 15
            WHEN I offer a loan for 70,000 without an option, with an option at 1,000,000 and with one at 1,200,000
            THEN the plain loan and the 1,200,000 option are accepted; the option below his real price is refused
            """)
    void optionPriceMustReachTheRealPrice() {
        assertNull(LoanTerms.refusal(70_000, 1_000_000, false, 0, false, 1_200_000, STABLE));
        assertNotNull(LoanTerms.refusal(70_000, 1_000_000, false, 1_000_000, false, 1_200_000, STABLE));
        assertNull(LoanTerms.refusal(70_000, 1_000_000, false, 1_200_000, false, 1_200_000, STABLE));
    }

    @Test
    @DisplayName("""
            GIVEN a top-15 player with a real price of 1,000,000 at a stable club (instalments worth 90%)
            WHEN I offer a plain loan, then a loan with an obligation to buy for 100,000 + 1,000,000,
             then for 100,000 + 900,000, and an obligation with no price
            THEN the plain loan is refused (top 15); 100,000 + 90% × 1,000,000 = 1,000,000 is accepted;
             100,000 + 90% × 900,000 = 910,000 is refused; an obligation needs a price
            """)
    void obligationIsASalePaidLater() {
        assertNotNull(LoanTerms.refusal(70_000, 1_000_000, true, 0, false, 1_000_000, STABLE));
        assertNull(LoanTerms.refusal(100_000, 1_000_000, true, 1_000_000, true, 1_000_000, STABLE));
        assertNotNull(LoanTerms.refusal(100_000, 1_000_000, true, 900_000, true, 1_000_000, STABLE));
        assertNotNull(LoanTerms.refusal(100_000, 1_000_000, false, 0, true, 1_000_000, STABLE));
    }

    @Test
    @DisplayName("""
            GIVEN an obligation to buy whose price alone is worth more than the player (2,000,000 for 1,000,000)
            WHEN the loan fee is negative (−500,000: the parent club would pay me)
            THEN it is refused, while a fee of 0 is accepted
            """)
    void aNegativeLoanFeeIsRefused() {
        assertNotNull(LoanTerms.refusal(-500_000, 1_000_000, false, 2_000_000, true, 1_000_000, STABLE));
        assertNull(LoanTerms.refusal(0, 1_000_000, false, 2_000_000, true, 1_000_000, STABLE));
    }
}
