package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Explicit owner record for a club, written when a human's
 * {@link ClubShareholding} crosses the ownership threshold. Ownership is
 * <em>derived</em> from shareholding (see
 * {@link com.footballmanagergamesimulator.service.OwnershipService#isOwner(long, long)}),
 * but this row makes the crossing explicit and queryable.
 */
@Entity
@Data
@Table(name = "ownership")
public class Ownership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long humanId;

    private long teamId;
}
