package com.footballmanagergamesimulator.training;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Assigns a coach (Human of a coach type) to a training unit. A unit may have
 * several coaches; a coach may cover several units. Workload and quality are
 * derived from the coach's specialization attributes at read time.
 */
@Entity
@Data
@Table(name = "unitCoachAssignment")
public class UnitCoachAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long coachId;

    /** GOALKEEPING, DEFENCE or ATTACK. */
    private String unit;

    @Version
    private long version;
}
