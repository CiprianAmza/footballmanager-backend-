package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A performance bonus of a transfer (design §3.5): the buyer pays the seller {@code amount} once, at the end of the
 * first season between {@code fromSeason} and {@code untilSeason} in which the condition is met.
 */
@Entity
@Data
@Table(name = "transfer_bonus")
public class TransferBonus {

    public static final String GOALS = "GOALS";
    public static final String TITLE = "TITLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long payerTeamId;
    private long payeeTeamId;
    /** GOALS: the player scores {@code target} goals for the payer in a season; TITLE: the payer wins its league. */
    private String type;
    private int target;
    private long amount;
    private int fromSeason;
    private int untilSeason;
    private boolean paid;
}
