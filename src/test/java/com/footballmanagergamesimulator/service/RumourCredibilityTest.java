package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TransferOffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RumourCredibilityTest {

    @Test
    @DisplayName("""
            GIVEN the same bid as a first offer, in talks, as a final offer and with the fee agreed
            WHEN the press rates the rumour
            THEN the first offer is early speculation (55-67%), the other three a strong story (80% or more)
            """)
    void talksBetweenTheClubsMakeAStrongerStory() {
        assertTrue(MediaNarrativeService.rumourCredibility(offer("pending")) < 70);
        for (String status : new String[]{TransferOffer.STATUS_TALKS, TransferOffer.STATUS_FINAL_OFFER,
                TransferOffer.STATUS_FEE_AGREED}) {
            assertTrue(MediaNarrativeService.rumourCredibility(offer(status)) >= 80, status);
        }
    }

    private static TransferOffer offer(String status) {
        TransferOffer offer = new TransferOffer();
        offer.setId(11);
        offer.setPlayerId(40);
        offer.setStatus(status);
        return offer;
    }
}
