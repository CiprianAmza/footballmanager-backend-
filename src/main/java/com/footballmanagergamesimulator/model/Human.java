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

    // Contract clauses
    @Column(columnDefinition = "int default 0")
    private int sellOnPercentage;      // 0-50: % of future sale sent to sellOnClubId
    @Column(columnDefinition = "bigint default 0")
    private long sellOnClubId;         // club that receives the sell-on fee
    @Column(columnDefinition = "int default 0")
    private int optionalExtensionYears; // 0-2: club can unilaterally extend
    @Column(columnDefinition = "bigint default 0")
    private long appearanceBonus;      // per match
    @Column(columnDefinition = "bigint default 0")
    private long goalBonus;            // per goal
    @Column(columnDefinition = "int default 0")
    private int relegationWageDrop;    // % wage reduction on relegation (e.g. 40 = 40%)

    // Pre-contract: player agreed to join this team when their contract expires
    @Column(columnDefinition = "bigint default 0")
    private long preContractTeamId;

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

    // Physical profile
    @Column(columnDefinition = "varchar(10) default 'Right'")
    private String preferredFoot = "Right"; // "Right", "Left", "Both"
    @Column(columnDefinition = "int default 180")
    private int heightCm = 180;
    @Column(columnDefinition = "int default 75")
    private int weightKg = 75;

    // Playing time tracking (for player happiness system)
    @Column(columnDefinition = "int default 0")
    private int consecutiveBenched; // number of consecutive matches not played

    @Column(columnDefinition = "boolean default false")
    private boolean wantsTransfer; // player has requested a transfer

    @Column(columnDefinition = "int default 0")
    private int seasonMatchesPlayed; // matches played this season

    // Individual training focus (overrides team focus for this player)
    @Column(columnDefinition = "varchar(30)")
    private String individualTrainingFocus; // "Attacking", "Defensive", "Tactical", "Physical", null = team focus

    @Column(columnDefinition = "varchar(30)")
    private String individualTrainingAttribute; // specific attribute name, e.g. "Finishing", "Composure", null = none

    @Column(columnDefinition = "varchar(50)")
    private String individualTrainingRole; // train as a specific role, e.g. "Advanced Forward", null = none

    /**
     * Manager information
     */
    private String tacticStyle;             // preferred tactic — used by AI for match simulation
    /**
     * Comma-separated list of tactics this manager knows and can switch to (the
     * preferred one above is always one of them). Weak managers know 2-3,
     * top-tier managers know 5+. Lets each coach feel distinct instead of
     * everyone defaulting to 442.
     */
    @Column(length = 200)
    private String knownTactics;
    private int managerReputation = 500;

    // Manager responsibilities
    @Column(columnDefinition = "boolean default true")
    private boolean attendPressConferences = true;

    @Column(columnDefinition = "boolean default false")
    private boolean viewFullMatch = false;

    @Column(columnDefinition = "boolean default true")
    private boolean watchGoalHighlights = true;

    /**
     * What the user wants to watch during matches: "NONE" (text only, no animations),
     * "GOALS_ONLY" (animations for goals), or "KEY_MOMENTS" (animations for goals
     * AND big chances / saves / misses). Source of truth — {@link #watchGoalHighlights}
     * is kept as a derived mirror for legacy save-file compatibility.
     */
    @Column(columnDefinition = "varchar(20) default 'GOALS_ONLY'")
    private String matchHighlightsLevel = "GOALS_ONLY";

    /**
     * Coaching attributes (used when typeId is a coach type: 5-10)
     * Scale: 1-20
     */
    @Column(columnDefinition = "int default 0")
    private int coachingAttacking;
    @Column(columnDefinition = "int default 0")
    private int coachingDefending;
    @Column(columnDefinition = "int default 0")
    private int coachingTactical;
    @Column(columnDefinition = "int default 0")
    private int coachingTechnical;
    @Column(columnDefinition = "int default 0")
    private int coachingMental;
    @Column(columnDefinition = "int default 0")
    private int coachingFitness;
    @Column(columnDefinition = "int default 0")
    private int coachingGK;
    @Column(columnDefinition = "int default 0")
    private int workingWithYoungsters;
    @Column(columnDefinition = "int default 0")
    private int motivating;

    @Column(name = "retired")
    @JsonProperty("isRetired")
    private boolean retired;

}
