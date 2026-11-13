package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.footballmanagergamesimulator.model.PlayerPersonality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInterestTest {

    @Test
    @DisplayName("""
            GIVEN a player rated 200
            WHEN clubs whose best three average 170 or 169 approach him
            THEN 170 (85% of his rating) is good enough, 169 is too weak, and only the best three count
            """)
    void squadTooWeakWhenBestThreeAreFarBelow() {
        assertFalse(PlayerInterest.teamTooWeak(200, List.of(180.0, 170.0, 160.0, 50.0)));
        assertTrue(PlayerInterest.teamTooWeak(200, List.of(179.0, 170.0, 158.0, 50.0)));
        assertTrue(PlayerInterest.teamTooWeak(200, List.of()));
    }

    @Test
    @DisplayName("""
            GIVEN a player who signed on day 300 of season 4
            WHEN his willingness to move is checked later
            THEN up to 181 days after signing he will not move, from 182 days (about six months) he may;
             a player with no signature on record is free to move
            """)
    void signatureWithinSixMonthsKeepsHim() {
        assertTrue(ContractSignature.signedRecently(4, 300, 4, 300));
        assertTrue(ContractSignature.signedRecently(4, 300, 5, 116));
        assertFalse(ContractSignature.signedRecently(4, 300, 5, 117));
        assertFalse(ContractSignature.signedRecently(0, 0, 5, 1));
    }


    @Test
    @DisplayName("""
            GIVEN a player rated 200 whose club has reputation 5000, and a club whose best three average 160
            WHEN players of each personality are asked about clubs of reputation 4800 and 4799
            THEN MONEY talks to anyone, FAME accepts at most 200 reputation below his club,
             AMBITION refuses because 160 is under 85% of 200
            """)
    void personalityDecidesWhichClubsHeTalksTo() {
        List<Double> squad = List.of(170.0, 160.0, 150.0);
        assertEquals(PlayerInterest.Reason.INTERESTED,
                PlayerInterest.byPersonality(PlayerPersonality.MONEY, 200, squad, 1_000, 5_000));
        assertEquals(PlayerInterest.Reason.INTERESTED,
                PlayerInterest.byPersonality(PlayerPersonality.FAME, 200, squad, 4_800, 5_000));
        assertEquals(PlayerInterest.Reason.CLUB_NOT_FAMOUS_ENOUGH,
                PlayerInterest.byPersonality(PlayerPersonality.FAME, 200, squad, 4_799, 5_000));
        assertEquals(PlayerInterest.Reason.TEAM_TOO_WEAK,
                PlayerInterest.byPersonality(PlayerPersonality.AMBITION, 200, squad, 9_000, 5_000));
        assertEquals(PlayerInterest.Reason.INTERESTED,
                PlayerInterest.byPersonality(PlayerPersonality.AMBITION, 200, List.of(180.0, 170.0, 160.0), 1_000, 5_000));
    }

    @Test
    @DisplayName("""
            GIVEN a MONEY player rated 200 earning 1000
            WHEN clubs whose best three average 160 and 220 want him
            THEN he asks 1000 + 10% + 1% per point above their average: 1500 from the first, 1100 from the stronger one
            """)
    void moneyPlayerAsksMoreTheMoreHeOutclassesTheClub() {
        assertEquals(1_500, PlayerInterest.moneyWage(1_000, 200, List.of(170.0, 160.0, 150.0, 20.0)));
        assertEquals(1_100, PlayerInterest.moneyWage(1_000, 200, List.of(230.0, 220.0, 210.0)));
    }


    @Test
    @DisplayName("""
            GIVEN a player rated 200 from a club of reputation 5000 who likes the club or the manager that wants him
            WHEN an ambitious and a fame-driven version of him are asked, and his demand of 1000 is computed
            THEN the bar drops to 75% of his rating and 400 reputation below his club, and he asks 15% less (850)
            """)
    void bondWithClubOrManagerMakesHimGiveUpMore() {
        List<Double> squad = List.of(160.0, 150.0, 140.0); // average 150 = 75% of 200
        assertEquals(PlayerInterest.Reason.TEAM_TOO_WEAK,
                PlayerInterest.byPersonality(PlayerPersonality.AMBITION, 200, squad, 5_000, 5_000, false));
        assertEquals(PlayerInterest.Reason.INTERESTED,
                PlayerInterest.byPersonality(PlayerPersonality.AMBITION, 200, squad, 5_000, 5_000, true));
        assertEquals(PlayerInterest.Reason.INTERESTED,
                PlayerInterest.byPersonality(PlayerPersonality.FAME, 200, squad, 4_600, 5_000, true));
        assertEquals(PlayerInterest.Reason.CLUB_NOT_FAMOUS_ENOUGH,
                PlayerInterest.byPersonality(PlayerPersonality.FAME, 200, squad, 4_599, 5_000, true));
        assertEquals(850, PlayerInterest.withBond(1_000, true));
        assertEquals(1_000, PlayerInterest.withBond(1_000, false));
    }

}
