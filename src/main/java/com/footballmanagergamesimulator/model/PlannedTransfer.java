package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An AI transfer decided at the start of the window and carried out on a later day of it (design §3.6,
 * decided 2026-11-12). Until then the player is "wanted"; a human club can still get in first.
 */
@Entity
@Data
@Table(name = "planned_transfer", indexes = {
        @Index(name = "ix_planned_transfer_status_day", columnList = "status, executeOnDay"),
        @Index(name = "ix_planned_transfer_player", columnList = "playerId")})
public class PlannedTransfer {

    public static final String PLANNED = "PLANNED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    /** 0 = free agent. */
    private long sellTeamId;
    private long buyTeamId;
    private long fee;
    private long wage;
    private int contractEndSeason;
    /** The season whose window planned it. */
    private int season;
    /** Absolute game day (season × 365 + day) on which it happens. */
    private int executeOnDay;
    private String status;
    private String cancelReason;
    /** A buy-back clause exercised: it may take the player even from a managed club. */
    @Column(columnDefinition = "boolean default false not null")
    private boolean buyBack;
    /** First absolute day on which the player shows as "wanted" (5 days before the planned day; postponing does not move it). */
    @Column(columnDefinition = "integer default 0 not null")
    private int wantedFromDay;
    /** Every club the market found able to sign him (the buyer included), comma separated: shown on "wanted". */
    @Column(length = 2000)
    private String contenderTeamIds;
}
