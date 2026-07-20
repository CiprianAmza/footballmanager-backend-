package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An admin decision made before an award ceremony. It is deliberately stored
 * separately from Award so the public history never exposes an unfinalised
 * winner and the statistical evidence can still be recalculated at ceremony.
 */
@Entity
@Data
@Table(name = "award_override", uniqueConstraints = @UniqueConstraint(
        name = "uk_award_override_scope",
        columnNames = {"season_number", "competition_id", "award_type"}))
public class AwardOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "season_number")
    private int seasonNumber;
    @Column(name = "competition_id")
    private long competitionId;
    @Column(name = "award_type")
    private String awardType;
    @Column(name = "winner_id")
    private long winnerId;
    @Column(name = "created_at")
    private long createdAt;
}
