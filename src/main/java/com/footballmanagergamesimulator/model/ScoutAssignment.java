package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "scout_assignment")
public class ScoutAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long scoutId;
    private String scoutName;
    private long playerId;
    private String playerName;
    private String playerPosition;
    private long playerTeamId;
    private String playerTeamName;
    private long teamId; // team that owns the scout

    private int startDay;
    private int endDay;
    private int season;
    private long cost; // scouting mission cost
    private boolean sameLeague;

    private String status; // "in_progress", "completed"

    // Revealed info (filled on completion)
    private Double revealedRating;
    private Double revealedPotential;
    private Long revealedTransferValue;
}
