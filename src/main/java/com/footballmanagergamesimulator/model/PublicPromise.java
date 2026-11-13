package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A target a manager announced in public (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): kept or missed at
 * season end, it moves his reputation (league ±150, cup ±75). Later the chairman remembers it too (step 8).
 */
@Entity
@Data
@Table(name = "public_promise")
public class PublicPromise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long managerId;
    private long teamId;
    private int season;
    private long competitionId;
    private String competitionName;
    /** A step of the squad-meeting ladders (TITLE, TOP_3, … / WIN, FINAL, …). */
    private String target;
    /** Reputation gained if kept, lost if missed. */
    private int stake;
    @Column(columnDefinition = "boolean default false not null")
    private boolean settled;
    @Column(columnDefinition = "boolean default false not null")
    private boolean kept;
}
