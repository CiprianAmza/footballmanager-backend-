package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An extra bonus promised to the squad for the next match (design DESIGN_START_CARIERA §6.4, decided 2026-11-13):
 * granted by the chairman, or paid by the manager out of his own money. Paid only if the match is won.
 */
@Entity
@Data
@Table(name = "match_bonus")
public class MatchBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long managerId;
    private int season;
    private int requestDay;
    private long amount;
    /** Paid by the manager himself after the chairman refused. */
    private boolean ownMoney;
    /** The chairman said no (kept so a second request in the month can be counted). */
    private boolean refused;
    private boolean settled;
    private boolean won;
}
