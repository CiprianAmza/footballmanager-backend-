package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "sponsorship")
public class Sponsorship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private String sponsorName;
    private String type; // "KIT", "STADIUM", "TRAINING", "GENERAL"
    private long annualValue;
    private int startSeason;
    private int endSeason;
    private int reputationRequirement;
    private String status; // "ACTIVE", "OFFERED", "EXPIRED", "REJECTED"
}
