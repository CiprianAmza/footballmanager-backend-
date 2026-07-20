package com.footballmanagergamesimulator.frontend;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.TransferOffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferOfferViewTest {

    @Test
    void includesCurrentPlayerSnapshotAndContractDuration() {
        TransferOffer offer = new TransferOffer();
        offer.setId(7);
        offer.setPlayerId(42);
        offer.setPlayerName("Test Player");

        Human player = new Human();
        player.setId(42);
        player.setPosition("ST");
        player.setRating(218.75);
        player.setAge(25);
        player.setContractEndSeason(6);
        player.setWage(125_000);
        player.setSeasonMatchesPlayed(19);

        TransferOfferView view = TransferOfferView.from(offer, player, 3);

        assertEquals("ST", view.position());
        assertEquals(218.75, view.rating());
        assertEquals(25, view.age());
        assertEquals(6, view.contractEndSeason());
        assertEquals(3, view.contractSeasonsRemaining());
        assertEquals(125_000, view.currentWage());
        assertEquals(19, view.seasonMatchesPlayed());
    }
}
