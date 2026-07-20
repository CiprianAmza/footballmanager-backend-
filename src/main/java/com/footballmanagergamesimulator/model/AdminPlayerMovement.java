package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Audit row and durable queue entry for a player movement forced from Admin.
 * PENDING rows are executed when {@code executionSeason} starts; immediate
 * movements are persisted as COMPLETED so the admin page keeps an audit trail.
 */
@Entity
@Data
@Table(name = "admin_player_movement", indexes = {
        @Index(name = "idx_admin_movement_status_season", columnList = "status,executionSeason"),
        @Index(name = "idx_admin_movement_player_status", columnList = "playerId,status")
})
public class AdminPlayerMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** PERMANENT, FREE_AGENT or LOAN. */
    @Column(nullable = false, length = 24)
    private String movementType;

    /** NOW or START_OF_SEASON. */
    @Column(nullable = false, length = 24)
    private String executionMode;

    /** PENDING, COMPLETED, CANCELLED or FAILED. */
    @Column(nullable = false, length = 24)
    private String status;

    private long playerId;
    private String playerName;

    private Long sourceTeamId;
    private String sourceTeamName;
    private long destinationTeamId;
    private String destinationTeamName;

    private long transferFee;
    private long wage;
    private int contractSeasons;
    private int loanSeasons;
    private int parentWageContribution;

    private int createdSeason;
    private int executionSeason;
    private long createdAt;
    private long completedAt;

    @Column(length = 1000)
    private String failureReason;
}
