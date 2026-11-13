package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** Which staff member a human-managed team delegated to coach one training type. */
@Entity
@Data
@Table(name = "training_delegation",
        uniqueConstraints = @UniqueConstraint(name = "uk_training_delegation", columnNames = {"teamId", "trainingType"}))
public class TrainingDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    /** A {@code TrainingType} name. */
    private String trainingType;
    private long staffId;
}
