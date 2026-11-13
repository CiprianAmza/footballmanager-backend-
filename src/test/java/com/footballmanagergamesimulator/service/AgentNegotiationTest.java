package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentPersonality;
import com.footballmanagergamesimulator.model.AgentRelationEvent;
import com.footballmanagergamesimulator.service.AgentNegotiation.Status;
import com.footballmanagergamesimulator.service.AgentNegotiation.Step;
import com.footballmanagergamesimulator.service.AgentNegotiation.Terms;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentNegotiationTest {

    private static Terms terms(AgentPersonality personality, int relation, int patience) {
        return new Terms(1_000, 50_000, personality, relation, patience);
    }

    @Test
    @DisplayName("""
            GIVEN relations from 0 to 100
            WHEN the agent's demand factor is computed
            THEN it is 1.0 at 50, 1.4 at the refusal threshold (and below), 0.75 at 100, linear in between
            """)
    void demandDependsOnRelation() {
        assertEquals(1.0, AgentNegotiation.demandFactor(50), 1e-9);
        assertEquals(1.4, AgentNegotiation.demandFactor(10), 1e-9);
        assertEquals(1.4, AgentNegotiation.demandFactor(0), 1e-9);
        assertEquals(1.2, AgentNegotiation.demandFactor(30), 1e-9);
        assertEquals(0.875, AgentNegotiation.demandFactor(75), 1e-9);
        assertEquals(0.75, AgentNegotiation.demandFactor(100), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a player worth 1000, a transfer value of 50000 and a neutral relation
            WHEN each kind of agent states his demand
            THEN Stability asks 700 for 4 years, Personal gain 750 for 2 years + a 2400 bonus,
             Turnover 750 for 2 years + a clause at 50000, and handing the demand back closes the deal
            """)
    void concreteDemandFollowsPersonality() {
        assertEquals(new Offer(700, 4, 0, 0), AgentNegotiation.agentDemand(terms(AgentPersonality.STABILITY, 50, 5)));
        assertEquals(new Offer(750, 2, 2_400, 0), AgentNegotiation.agentDemand(terms(AgentPersonality.PERSONAL_GAIN, 50, 5)));
        assertEquals(new Offer(750, 2, 0, 50_000), AgentNegotiation.agentDemand(terms(AgentPersonality.TURNOVER, 50, 5)));

        for (AgentPersonality p : AgentPersonality.values()) {
            Terms t = terms(p, 50, 5);
            Step after = AgentNegotiation.offer(t, AgentNegotiation.start(t), AgentNegotiation.agentDemand(t));
            assertEquals(Status.AGREED, after.status(), p.name());
            assertEquals(AgentRelationEvent.CONTRACT_AGREED, after.event());
        }
    }

    @Test
    @DisplayName("""
            GIVEN a Stability agent with patience 4 and an offer worth 90% of the player
            WHEN I repeat it every round and then refuse his final offer
            THEN rounds 1 and 2 stay open, after round 3 he puts a take-it-or-leave-it offer,
             refusing it ends the talks with a relation penalty and no further offer is allowed
            """)
    void roundsEndWithATakeItOrLeaveItOffer() {
        Terms t = terms(AgentPersonality.STABILITY, 50, 4);
        Offer mine = new Offer(900, 1, 0, 0);

        Step step = AgentNegotiation.start(t);
        step = AgentNegotiation.offer(t, step, mine);
        assertEquals(Status.OPEN, step.status());
        assertEquals(2, step.round());
        assertNull(step.event());
        step = AgentNegotiation.offer(t, step, mine);
        assertEquals(Status.OPEN, step.status());
        step = AgentNegotiation.offer(t, step, mine);
        assertEquals(Status.FINAL_OFFER, step.status());
        assertEquals(4, step.round());
        assertEquals(new Offer(700, 4, 0, 0), step.agentOffer());

        Step finalStep = step;
        assertThrows(IllegalStateException.class, () -> AgentNegotiation.offer(t, finalStep, mine));
        Step refused = AgentNegotiation.answerFinalOffer(step, false);
        assertEquals(Status.FINAL_REJECTED, refused.status());
        assertEquals(AgentRelationEvent.FINAL_OFFER_REJECTED, refused.event());
        assertEquals(Status.AGREED, AgentNegotiation.answerFinalOffer(step, true).status());
    }

    @Test
    @DisplayName("""
            GIVEN a player worth 1000 and one-year offers with no extras
            WHEN the offer is too far from what he is worth
            THEN Personal gain leaves under 80%, Turnover under 70%, Stability under 60%, no agent under 50%,
             already in round 1 and with a relation penalty
            """)
    void agentWalksAwayFromOffersTooFarBelow() {
        assertEquals(Status.WALKED_AWAY, firstOffer(AgentPersonality.PERSONAL_GAIN, 790).status());
        assertEquals(Status.OPEN, firstOffer(AgentPersonality.PERSONAL_GAIN, 800).status());
        assertEquals(Status.WALKED_AWAY, firstOffer(AgentPersonality.TURNOVER, 690).status());
        assertEquals(Status.OPEN, firstOffer(AgentPersonality.TURNOVER, 700).status());
        assertEquals(Status.WALKED_AWAY, firstOffer(AgentPersonality.STABILITY, 590).status());
        assertEquals(Status.OPEN, firstOffer(AgentPersonality.STABILITY, 600).status());
        assertEquals(Status.WALKED_AWAY, firstOffer(null, 490).status());
        assertEquals(Status.OPEN, firstOffer(null, 500).status());
        assertEquals(AgentRelationEvent.AGENT_WALKED_AWAY, firstOffer(AgentPersonality.STABILITY, 590).event());
    }

    @Test
    @DisplayName("""
            GIVEN an agent at the refusal threshold (relation 10) and one at 100
            WHEN I offer one-year contracts
            THEN the first needs 140% of the player's value, the second accepts 75%
            """)
    void relationMovesTheBar() {
        Terms bad = terms(AgentPersonality.STABILITY, 10, 5);
        assertEquals(Status.OPEN, AgentNegotiation.offer(bad, AgentNegotiation.start(bad), new Offer(1_390, 1, 0, 0)).status());
        assertEquals(Status.AGREED, AgentNegotiation.offer(bad, AgentNegotiation.start(bad), new Offer(1_400, 1, 0, 0)).status());
        Terms good = terms(AgentPersonality.STABILITY, 100, 5);
        assertEquals(Status.AGREED, AgentNegotiation.offer(good, AgentNegotiation.start(good), new Offer(750, 1, 0, 0)).status());
    }

    private static Step firstOffer(AgentPersonality personality, long wage) {
        Terms t = terms(personality, 50, 5);
        return AgentNegotiation.offer(t, AgentNegotiation.start(t), new Offer(wage, 1, 0, 0));
    }
}
