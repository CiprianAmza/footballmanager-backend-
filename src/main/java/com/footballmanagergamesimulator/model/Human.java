package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Date;

@Entity
@Data
@Table(name="human")
public class Human {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private long id;

    /**
     *  Relation ids
     */
    private Long teamId;
    private long agentId;
    private long skillsId;
    private long typeId;

    /**
     * General information
     */
    private int age;
    private int shirtNumber;
    private long salary;
    private long wealth;
    private String name;
    private String position;
    private String agreedPlayingTime;
    private Date contractEndDate;
    private Date contractStartDate;

    /**
     * Contract information
     */
    private int contractEndSeason;
    private long wage;
    private long releaseClause;

    /**
     * Stats information
     */
    private int currentAbility;
    private int potentialAbility;
    private long transferValue;
    private double rating;
    private double fitness;
    private double morale;
    private String currentStatus;
    private long seasonCreated;

    private double bestEverRating;
    private int seasonOfBestEverRating;

    // Playing time tracking (for player happiness system)
    @Column(columnDefinition = "int default 0")
    private int consecutiveBenched; // number of consecutive matches not played

    @Column(columnDefinition = "boolean default false")
    private boolean wantsTransfer; // player has requested a transfer

    @Column(columnDefinition = "int default 0")
    private int seasonMatchesPlayed; // matches played this season

    /**
     * Manager information
     */
    private String tacticStyle;
    private int managerReputation = 500;

    // Manager responsibilities
    @Column(columnDefinition = "boolean default true")
    private boolean attendPressConferences = true;

    @Column(columnDefinition = "boolean default false")
    private boolean viewFullMatch = false;

    @Column(name = "retired")
    @JsonProperty("isRetired")
    private boolean retired;

}
