package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "facility_upgrade")
public class FacilityUpgrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private String facilityType; // "STADIUM", "TRAINING", "YOUTH_ACADEMY", "SCOUTING", "MEDICAL"
    private int currentLevel;
    private int targetLevel;
    private long cost;
    private int startDay;
    private int startSeason;
    private int durationDays;
    private boolean completed;
}
