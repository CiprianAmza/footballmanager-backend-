package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentPersonality;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;
import com.footballmanagergamesimulator.service.ContractPoints.SquadMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractPointsTest {

    private static final List<SquadMember> SQUAD = List.of(
            new SquadMember(250, 9_000), new SquadMember(240, 12_000), new SquadMember(230, 7_000),
            new SquadMember(200, 5_000), new SquadMember(180, 3_000));

    @Test
    @DisplayName("""
            GIVEN a squad paying 12000, 9000, 7000, 5000, 3000 (wages not in rating order)
            WHEN players of different ratings ask for a wage there
            THEN a player rated below two members asks for the 3rd highest wage (7000),
             the best asks for the highest, one below everyone for the lowest, and ties do not count as above
            """)
    void demandFollowsTheSquadWageHierarchy() {
        assertEquals(7_000, ContractPoints.demandedWage(235, SQUAD));
        assertEquals(12_000, ContractPoints.demandedWage(260, SQUAD));
        assertEquals(3_000, ContractPoints.demandedWage(100, SQUAD));
        assertEquals(9_000, ContractPoints.demandedWage(240, SQUAD));
    }

    @Test
    @DisplayName("""
            GIVEN a club with no paid players
            WHEN a player asks for a wage
            THEN he falls back to the rating-based wage
            """)
    void emptySquadFallsBackToRatingWage() {
        assertEquals(WageService.baseWage(200), ContractPoints.demandedWage(200, List.of()));
    }

    @Test
    @DisplayName("""
            GIVEN a demand of 100 and an offer of 90 a month for 3 years, no bonus, no clause
            WHEN agents of each personality value it
            THEN a neutral, Personal gain or Turnover agent sees 100, a Stability agent 110
            """)
    void durationIsWorthMoreToStability() {
        Offer offer = new Offer(90, 3, 0, 0);
        assertEquals(100.0, ContractPoints.offerPoints(offer, 100, 1_000, null), 1e-9);
        assertEquals(100.0, ContractPoints.offerPoints(offer, 100, 1_000, AgentPersonality.PERSONAL_GAIN), 1e-9);
        assertEquals(100.0, ContractPoints.offerPoints(offer, 100, 1_000, AgentPersonality.TURNOVER), 1e-9);
        assertEquals(110.0, ContractPoints.offerPoints(offer, 100, 1_000, AgentPersonality.STABILITY), 1e-9);
        assertEquals(1.1, ContractPoints.share(offer, 100, 1_000, AgentPersonality.STABILITY), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a demand of 100 and a 2-year offer of 80 a month with a 960 signing bonus
            WHEN it is valued
            THEN the bonus counts 960 / 24 months = 40 (80 for Personal gain) and duration 5 (10 for Stability)
            """)
    void signingBonusIsSpreadOverTheContract() {
        Offer offer = new Offer(80, 2, 960, 0);
        assertEquals(125.0, ContractPoints.offerPoints(offer, 100, 1_000, null), 1e-9);
        assertEquals(165.0, ContractPoints.offerPoints(offer, 100, 1_000, AgentPersonality.PERSONAL_GAIN), 1e-9);
        assertEquals(130.0, ContractPoints.offerPoints(offer, 100, 1_000, AgentPersonality.STABILITY), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a demand of 100, a 1-year offer of 100 and a transfer value of 1000
            WHEN the release clause is 1000, 1001 or absent
            THEN only the clause at or under the transfer value adds 10 (20 for Turnover)
            """)
    void lowReleaseClauseIsWorthMoreToTurnover() {
        assertEquals(110.0, ContractPoints.offerPoints(new Offer(100, 1, 0, 1_000), 100, 1_000, null), 1e-9);
        assertEquals(120.0, ContractPoints.offerPoints(new Offer(100, 1, 0, 1_000), 100, 1_000, AgentPersonality.TURNOVER), 1e-9);
        assertEquals(100.0, ContractPoints.offerPoints(new Offer(100, 1, 0, 1_001), 100, 1_000, AgentPersonality.TURNOVER), 1e-9);
        assertEquals(100.0, ContractPoints.offerPoints(new Offer(100, 1, 0, 0), 100, 1_000, AgentPersonality.TURNOVER), 1e-9);
    }
}
