package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "friendly_match")
public class FriendlyMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int season;
    @Column(name = "match_day")
    private int day; // calendar day (1-365)

    private long homeTeamId;
    private long awayTeamId;

    private String homeTeamName;
    private String awayTeamName;

    // Scores (-1 = not yet played)
    @Column(columnDefinition = "int default -1")
    private int homeGoals = -1;
    @Column(columnDefinition = "int default -1")
    private int awayGoals = -1;

    // Status: SCHEDULED, COMPLETED, CANCELLED
    @Column(columnDefinition = "varchar(20) default 'SCHEDULED'")
    private String status = "SCHEDULED";

    // Which team scheduled this friendly (the human team)
    private long scheduledByTeamId;

    // Match stats summary (optional, stored inline for simplicity)
    private int homePossession;
    private int awayPossession;
    private int homeShots;
    private int awayShots;
    private int homeShotsOnTarget;
    private int awayShotsOnTarget;

    // Linked calendar event ID (for cancellation)
    @Column(columnDefinition = "bigint default 0")
    private long calendarEventId;
}
