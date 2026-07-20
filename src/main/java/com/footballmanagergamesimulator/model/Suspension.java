package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "suspension")
public class Suspension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long teamId;
    private long competitionId;
    private int matchesBanned;
    private int matchesServed;
    private String reason; // "RED_CARD", "ACCUMULATED_YELLOWS", "VIOLENT_CONDUCT"
    private int seasonNumber;
    private int dayIssued;
    /** Match-event that triggered the ban. Used to make card processing idempotent. */
    @Column(name = "source_match_event_id")
    private long sourceMatchEventId;
    private boolean active;
}
