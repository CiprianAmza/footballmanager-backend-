package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** The last month a league's players got their monthly training update. */
@Entity
@Data
@Table(name = "training_update_marker",
        uniqueConstraints = @UniqueConstraint(name = "uk_training_update_marker_league", columnNames = "leagueId"))
public class TrainingUpdateMarker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long leagueId;
    /** {@code TrainingUpdateSchedule.monthKey} of the last update. */
    private int lastMonthKey;
}
