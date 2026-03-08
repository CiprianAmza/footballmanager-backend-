package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "season_objective")
public class SeasonObjective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int seasonNumber;
    private String objectiveType; // "league_position", "cup_round", "european_qualification"
    private long competitionId;
    private String competitionName;
    private int targetValue; // e.g., position 3 means "finish top 3"
    private int actualValue; // filled at end of season
    private String status; // "active", "achieved", "failed"
    private String importance; // "critical", "high", "medium", "low"
    private String description;
}
