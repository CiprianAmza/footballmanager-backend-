package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * How many times a player of a human-managed team trained one attribute since the last
 * monthly update, and the last month he trained it at all.
 *
 * <p>Only human teams store counters; AI counters are derived on the update day. A row
 * belongs to the team the player trained with: after a transfer, rows of another team are
 * ignored, so counters start from 0 at the new club.
 */
@Entity
@Data
@Table(name = "player_training_counter",
        uniqueConstraints = @UniqueConstraint(name = "uk_player_training_counter",
                columnNames = {"playerId", "teamId", "attribute"}),
        indexes = @Index(name = "idx_player_training_counter_team", columnList = "teamId"))
public class PlayerTrainingCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private long teamId;
    private String attribute;
    /** Sessions that trained this attribute since the last monthly update. */
    private int sessions;
    /** {@code TrainingUpdateSchedule.monthKey} of the last session that trained it. */
    private int lastTrainedMonthKey;
}
