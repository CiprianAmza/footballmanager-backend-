package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A human's percentage stake in a club. Crossing the ownership threshold
 * (config-driven, default {@code >50%}) makes the human the club owner — see
 * {@link com.footballmanagergamesimulator.service.OwnershipService}.
 */
@Entity
@Data
@Table(name = "club_shareholding")
public class ClubShareholding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long humanId;

    private long teamId;

    /** Percentage of the club owned by this human (0-100).
     *  Column renamed off the reserved word PERCENT (H2 SQL keyword). */
    @Column(name = "percent_stake", columnDefinition = "double default 0")
    private double percent;
}
