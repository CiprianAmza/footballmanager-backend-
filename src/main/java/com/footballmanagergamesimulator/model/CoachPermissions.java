package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Owner-imposed coach-permission matrix for a single team (Boardroom Faza 4).
 * A human owner ({@link Ownership}) toggles how much autonomy the team's coach
 * (human OR AI) has. Enforced server-side in every coach action point.
 *
 * <p>Default = fully permissive (all toggles true, no cap, no XI locks), so a
 * team without a row behaves exactly as before — enforcement is additive.
 */
@Entity
@Data
@Table(name = "coach_permissions")
public class CoachPermissions {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(unique = true)
    private long teamId;

    @Column(columnDefinition = "boolean default true")
    private boolean canBuyPlayers = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canSellPlayers = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canNegotiateContracts = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canPickXI = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canChangeFormationTactics = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canSetTraining = true;
    @Column(columnDefinition = "boolean default true")
    private boolean canSetSetPieces = true;

    /** Max single transfer offer the coach may make. -1 = no cap. */
    @Column(columnDefinition = "bigint default -1")
    private long transferBudgetCap = -1;

    /** JSON list of {positionIndex, playerId} the owner has locked into the XI. */
    @Column(length = 4000)
    private String lockedSlots;
}
