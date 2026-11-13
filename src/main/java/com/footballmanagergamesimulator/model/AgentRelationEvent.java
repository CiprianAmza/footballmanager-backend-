package com.footballmanagergamesimulator.model;

/** What moves an agent's relation with a manager, and by how much (design §3.2, decided 2026-11-11). */
public enum AgentRelationEvent {
    CONTRACT_AGREED(5),
    AGENT_WALKED_AWAY(-10),
    FINAL_OFFER_REJECTED(-5),
    PROMISE_BROKEN(-15),
    ASKED_PLAYER_TO_FIRE_AGENT(-20);

    private final int delta;

    AgentRelationEvent(int delta) {
        this.delta = delta;
    }

    public int delta() {
        return delta;
    }
}
