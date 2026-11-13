package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** A player agent: a world entity, like journalists and owners. Players point to it via {@code Human.agentId}. */
@Entity
@Data
@Table(name = "agent")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String name;
    /** Nation whose players the agent was created for (see {@code NationService}). */
    private long nationId;

    @Enumerated(EnumType.STRING)
    private AgentPersonality personality;

    /** Negotiation rounds before the agent leaves the table, 3–10. */
    private int patience;
}
