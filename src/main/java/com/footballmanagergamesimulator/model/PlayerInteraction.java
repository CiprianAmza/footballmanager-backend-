package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "player_interaction")
public class PlayerInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long teamId;
    private int seasonNumber;
    @Column(name = "interaction_day")
    private int day;
    private String interactionType; // "CONTRACT_COMPLAINT", "PLAYING_TIME_REQUEST", "PRAISE", "CONFLICT", "TRANSFER_REQUEST", "UNHAPPY"
    private String message;
    private boolean resolved;
    private String playerResponse; // nullable
}
