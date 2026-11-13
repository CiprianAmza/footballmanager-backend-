package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentRelationEvent;
import com.footballmanagergamesimulator.model.RelationParty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The agent's view of a manager (design §3.2): the relation AGENT → MANAGER in {@link RelationService}, shared by
 * all the agent's players. Starts at 50; below 10 the agent refuses to negotiate with that manager at all.
 */
@Service
public class AgentRelationService {

    public static final int START = RelationService.NEUTRAL;
    public static final int MIN = RelationService.MIN;
    public static final int MAX = RelationService.MAX;
    /** Below this the agent will not sit at the table with the manager. */
    public static final int REFUSAL_THRESHOLD = 10;

    @Autowired RelationService relations;

    public int relation(long agentId, long managerId) {
        return relations.relation(RelationParty.AGENT, agentId, RelationParty.MANAGER, managerId);
    }

    /** Players without an agent (agentId 0) negotiate directly and never refuse on these grounds. */
    public boolean refusesToNegotiate(long agentId, long managerId) {
        return agentId > 0 && relation(agentId, managerId) < REFUSAL_THRESHOLD;
    }

    /** Applies an event and returns the new relation, kept within 0–100. No-op for agentless players. */
    @Transactional
    public int record(long agentId, long managerId, AgentRelationEvent event) {
        if (agentId <= 0) return START;
        return relations.change(RelationParty.AGENT, agentId, RelationParty.MANAGER, managerId, event.delta());
    }
}
