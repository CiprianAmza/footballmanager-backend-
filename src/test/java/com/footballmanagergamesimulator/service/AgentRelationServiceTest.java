package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentRelationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AgentRelationService.class, RelationService.class})
class AgentRelationServiceTest {

    private static final long AGENT = 7L;
    private static final long ME = 100L;
    private static final long OTHER_MANAGER = 200L;

    @Autowired private AgentRelationService relations;
    @Autowired private com.footballmanagergamesimulator.repository.HumanEntityRelationRepository repository;

    @Test
    @DisplayName("""
            GIVEN an agent who never dealt with me
            WHEN his relation with me is read
            THEN it is the neutral 50 and he is willing to negotiate
            """)
    void unknownRelationStartsNeutral() {
        assertEquals(50, relations.relation(AGENT, ME));
        assertFalse(relations.refusesToNegotiate(AGENT, ME));
    }

    @Test
    @DisplayName("""
            GIVEN a neutral agent
            WHEN I ask a player to fire him (−20) and he then walks away from a negotiation (−10) and
             I reject his final offer (−5) and break a promise (−15)
            THEN the relation drops 50 → 0, he refuses to negotiate with me,
             and another manager still finds him at 50
            """)
    void eventsMoveTheRelationAndBelowTenTheAgentRefuses() {
        assertEquals(30, relations.record(AGENT, ME, AgentRelationEvent.ASKED_PLAYER_TO_FIRE_AGENT));
        assertEquals(20, relations.record(AGENT, ME, AgentRelationEvent.AGENT_WALKED_AWAY));
        assertEquals(15, relations.record(AGENT, ME, AgentRelationEvent.FINAL_OFFER_REJECTED));
        assertFalse(relations.refusesToNegotiate(AGENT, ME));
        assertEquals(0, relations.record(AGENT, ME, AgentRelationEvent.PROMISE_BROKEN));

        assertTrue(relations.refusesToNegotiate(AGENT, ME));
        assertEquals(50, relations.relation(AGENT, OTHER_MANAGER));
    }

    @Test
    @DisplayName("""
            GIVEN an agent brought to 95 with me by nine agreed contracts
            WHEN we agree two more (+5 each)
            THEN the relation stops at 100
            """)
    void relationIsCappedAt100() {
        for (int i = 0; i < 9; i++) relations.record(AGENT, ME, AgentRelationEvent.CONTRACT_AGREED);
        assertEquals(95, relations.relation(AGENT, ME));
        assertEquals(100, relations.record(AGENT, ME, AgentRelationEvent.CONTRACT_AGREED));
        assertEquals(100, relations.record(AGENT, ME, AgentRelationEvent.CONTRACT_AGREED));
    }

    @Test
    @DisplayName("""
            GIVEN a player without an agent (agentId 0)
            WHEN events are recorded against "his agent"
            THEN nothing is stored and he never refuses to negotiate
            """)
    void agentlessPlayersAreUnaffected() {
        relations.record(0L, ME, AgentRelationEvent.PROMISE_BROKEN);
        relations.record(0L, ME, AgentRelationEvent.PROMISE_BROKEN);
        relations.record(0L, ME, AgentRelationEvent.PROMISE_BROKEN);
        relations.record(0L, ME, AgentRelationEvent.PROMISE_BROKEN);
        assertFalse(relations.refusesToNegotiate(0L, ME));
        assertEquals(0, repository.count());
    }
}
