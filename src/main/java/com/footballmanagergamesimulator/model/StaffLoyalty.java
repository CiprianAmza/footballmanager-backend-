package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * How far a member of the coaching staff stands with the human manager rather than with the general director
 * (design DESIGN_START_CARIERA §5.2, decided 2026-11-13). 0-100, hidden from the manager; the assistant's
 * remarks are the only hint.
 */
@Entity
@Data
@Table(name = "staff_loyalty", uniqueConstraints = @UniqueConstraint(columnNames = {"staffId"}))
public class StaffLoyalty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long staffId;
    private long teamId;
    /** 100 = entirely the manager's man, 0 = entirely the director's. */
    private int loyalty;
    /** Seasons at the club when the manager arrived. */
    private int tenureSeasons;
    /** Hired by the manager himself. */
    private boolean broughtByManager;
    /** Last season the manager praised him in public (once a season counts). */
    private int lastPraisedSeason;
}
