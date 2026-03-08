package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "match_event")
public class MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long competitionId;
    private int seasonNumber;
    private int roundNumber;
    private long teamId1; // home team
    private long teamId2; // away team
    @Column(name = "event_minute")
    private int minute; // 1 to 90
    private String eventType; // "goal", "assist", "yellow_card", "red_card", "substitution"
    private long playerId;
    private String playerName;
    private long teamId; // which team the event belongs to
    private String details; // extra info like "Header from corner", "Long range shot"
}
