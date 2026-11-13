package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** A fine taken from a player under his club's code of conduct; one per offence event. */
@Entity
@Data
@Table(name = "conduct_fine", uniqueConstraints = @UniqueConstraint(columnNames = {"matchEventId"}))
public class ConductFine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long playerId;
    private String playerName;
    /** The card that caused it. */
    private long matchEventId;
    private String offence;
    private double fineWeeks;
    private long amount;
    private int season;
}
