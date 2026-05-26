package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A coaching job proposed to a (human) user by another team.
 * Sits next to ManagerInbox: the inbox row is for display, this entity holds
 * the structured offer state and drives the accept/decline workflow.
 *
 * Lifecycle: PENDING -> ACCEPTED | DECLINED | EXPIRED.
 * Game advancing is paused while at least one PENDING offer exists for the user.
 */
@Entity
@Data
@Table(name = "job_offer")
public class JobOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** User receiving the offer. */
    private int userId;

    /** Team making the offer. */
    private long teamId;
    private String teamName;
    /** Team reputation snapshot at the time of the offer (so UI can show
     *  "Top-flight side" / "Mid-table" without an extra fetch). */
    private int teamReputation;

    /** Proposed contract terms — currently informational. */
    private long offeredWage;
    private long signingBonus;
    private int contractLengthSeasons;

    /** Free-form reason from the offering team (e.g. "We want to challenge
     *  for the title next season"). */
    @Column(length = 500)
    private String pitch;

    /** Day + season the offer was made. */
    private int seasonOffered;
    private int dayOffered;

    /** Last day the user can respond — automatically becomes EXPIRED past this. */
    private int expiresOnDay;

    /** PENDING | ACCEPTED | DECLINED | EXPIRED */
    private String status = "PENDING";

    /** Where the user manager currently coaches (snapshot, to revert if needed). */
    private long currentTeamId;
}
