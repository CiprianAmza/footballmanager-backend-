package com.footballmanagergamesimulator.training;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Assigns a player to a training unit (GOALKEEPING / DEFENCE / ATTACK).
 * Auto-assigned by position on first load; the user may override, in which
 * case {@link #autoAssigned} is cleared so re-syncs don't clobber the choice.
 */
@Entity
@Data
@Table(name = "playerUnitAssignment")
public class PlayerUnitAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long playerId;

    /** GOALKEEPING, DEFENCE or ATTACK. */
    private String unit;

    /** True while the assignment was derived from position and not user-pinned. */
    private boolean autoAssigned;

    @Version
    private long version;
}
