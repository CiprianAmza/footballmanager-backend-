package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One user verdict on one generated presentation phase (Phase Lab). The
 * (strategy, scenario, outcome, seed) tuple regenerates the exact phase
 * deterministically, so the feature vector never needs storing.
 */
@Entity
@Data
@Table(name = "phase_rating", indexes = {
        @Index(name = "idx_phase_rating_scenario", columnList = "scenario")
})
public class PhaseRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String strategy;
    private String scenario;
    /** Terminal the phase resolved into: goal, shot_saved, … or possession. */
    private String outcome;
    private long seed;
    /** 1 (drop it) … 5 (love it). */
    private int rating;
    private long createdAt;
}
