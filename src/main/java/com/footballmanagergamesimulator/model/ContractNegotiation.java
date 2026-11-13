package com.footballmanagergamesimulator.model;

import com.footballmanagergamesimulator.service.AgentNegotiation;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A contract negotiation in progress (or finished) between a manager and a player's agent (design §3.3).
 * The terms are frozen when it starts; while one is OPEN or FINAL_OFFER the game day cannot advance.
 */
@Entity
@Data
@Table(name = "contract_negotiation")
public class ContractNegotiation {

    public static final String KIND_RENEWAL = "RENEWAL";
    /** Personal terms after the selling club accepted my fee; agreement completes the transfer. */
    public static final String KIND_TRANSFER = "TRANSFER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String kind;
    private long playerId;
    private long teamId;
    private long managerId;
    /** 0 = the player negotiates for himself. */
    private long agentId;
    private int season;
    /** TRANSFER only: the accepted transfer offer and its fee. */
    private long transferOfferId;
    private long transferFee;
    /** REFUSED only: why the agent would not talk ({@link com.footballmanagergamesimulator.service.PlayerInterest.Reason}). */
    private String refusalReason;
    /** RENEWAL only: null = I did not ask the player to fire his agent; FIRED / KEPT = his answer. */
    private String fireAgentOutcome;

    // Frozen terms
    private long playerValue;
    private long transferValue;
    @Enumerated(EnumType.STRING)
    private AgentPersonality personality;
    private int relation;
    private int rounds;

    // Current step
    @Enumerated(EnumType.STRING)
    private AgentNegotiation.Status status;
    private int round;
    private long agentWage;
    private int agentYears;
    private long agentSigningBonus;
    private long agentReleaseClause;

    // My last offer (0 = none yet)
    private long lastWage;
    private int lastYears;
    private long lastSigningBonus;
    private long lastReleaseClause;
}
