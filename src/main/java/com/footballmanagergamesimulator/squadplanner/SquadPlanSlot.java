package com.footballmanagergamesimulator.squadplanner;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A single depth-chart slot in a user's squad plan.
 *
 * The plan is keyed by (userId, teamId, seasonOffset) where seasonOffset is
 * 0 = Current Season, 1 = Next Season, 2 = Season After — three distinct
 * persisted horizons. Within a horizon, slots are grouped by canonical
 * {@link #position} and ordered by {@link #depthOrder} (1 = first choice).
 *
 * A slot may reference an existing {@link #playerId}, or be empty (playerId
 * null) to represent a recruitment need. {@link #locked} slots are protected
 * from the assistant (see SquadPlannerAssistant).
 *
 * {@link #role} / {@link #roleFamiliarity} are stored as loose contract-ready
 * columns for the future PlayerPosition/role-familiarity model; this feature
 * does not compute familiarity, it only persists what the user sets.
 */
@Entity
@Data
@Table(name = "squadPlanSlot")
public class SquadPlanSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Owning user (career manager). Ownership is enforced on every mutation. */
    private int userId;

    /** Team the plan belongs to. */
    private long teamId;

    /** 0 = Current Season, 1 = Next Season, 2 = Season After. */
    private int seasonOffset;

    /** Game season index at the time the plan row was created — used to detect rollover. */
    private int baseSeason;

    /** Canonical position code: GK, DR, DC, DL, DM, MC, ML, MR, AMC, AML, AMR, ST. */
    private String position;

    /** 1 = first choice, 2 = second choice, ... Higher = deeper on the chart. */
    private int depthOrder;

    /** Referenced player, or null for an empty / recruitment slot. */
    private Long playerId;

    /** Free-text role (optionally validated against PlayerRoleService). Contract-ready, nullable. */
    private String role;

    /** Reserved for the future multi-position/role-familiarity model. Nullable, unused here. */
    private Integer roleFamiliarity;

    /** 0 = normal, 1 = high, 2 = key. Higher = more important to the plan. */
    private int priority;

    private boolean plannedSale;
    private boolean plannedLoan;
    private boolean youthPromotion;

    /** Marked (or assistant-flagged) as a position that needs recruitment. */
    private boolean recruitmentNeed;

    /** User-locked: the assistant must never overwrite or clear this slot. */
    private boolean locked;

    @Column(length = 500)
    private String notes;

    /** Optimistic locking to protect concurrent plan edits. */
    @Version
    private long version;
}
