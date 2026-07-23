package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Date;

@Entity
@Data
@Table(name="human", indexes = {
        @Index(name = "idx_human_team_type", columnList = "teamId,typeId"),
        @Index(name = "idx_human_type_retired", columnList = "typeId,retired")
})
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

    // Face descriptor — compact indices for FE layered-face rendering (see FaceGenerator).
    @Column(columnDefinition = "int default 0")
    private int baseFaceId;
    @Column(columnDefinition = "int default 0")
    private int skinTone;
    @Column(columnDefinition = "int default 0")
    private int hairStyle;
    @Column(columnDefinition = "int default 0")
    private int hairColor;
    @Column(columnDefinition = "int default 0")
    private int eyeColor;
    // Shape indices (independent of colour) — each picks a distinct masculine component on the FE.
    @Column(columnDefinition = "int default 0")
    private int faceShape;
    @Column(columnDefinition = "int default 0")
    private int noseShape;
    @Column(columnDefinition = "int default 0")
    private int eyeShape;
    @Column(columnDefinition = "int default 0")
    private int mouthShape;
    @Column(columnDefinition = "int default 0")
    private int browShape;
    // Exotic species (whole-nation mapping in FaceGenerator); "human" = default earthly face.
    @Column(columnDefinition = "varchar(20) default 'human'")
    private String species = "human";

    // Playing time tracking (for player happiness system)
    @Column(columnDefinition = "int default 0")
    private int consecutiveBenched; // number of consecutive matches not played

    @Column(columnDefinition = "boolean default false")
    private boolean wantsTransfer; // player has requested a transfer

    /**
     * Editor-only one-club-player rule. Protected players are excluded from every
     * transfer, loan, free-agent and pre-contract workflow. If their deal is not
     * renewed, they retire when it expires instead of joining another club.
     */
    @Column(columnDefinition = "boolean default false")
    private boolean willNeverLeave;

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

    /**
     * Editor-controlled AI trait. When enabled, this manager ignores their
     * preferred shape and tactical comfort level and always selects the
     * highest-valued formation and tactical setup available to the squad.
     */
    @Column(columnDefinition = "boolean default false")
    private boolean alwaysUseBestPossibleTactic;

    private int managerReputation = 500;

    /** Total salary the manager has earned across their whole career (accrued monthly). */
    @Column(columnDefinition = "bigint default 0")
    private long careerEarnings = 0;

    /**
     * Manager's OFFENSIVE coaching ability on a 0-100 scale. Unlike {@link #managerReputation}
     * (derived from the club's reputation), this is the coach's own skill on the attacking side:
     * it amplifies the squad's effective attack and makes attacking tactics suit him better — so a
     * lopsided coach develops an identity. Seeded at generation from the club's level with
     * independent noise, so a coach can be (e.g.) attack-strong but defence-weak.
     */
    @Column(columnDefinition = "double precision default 50")
    private double offensiveAbility = 50;

    /** Manager's DEFENSIVE coaching ability on a 0-100 scale (see {@link #offensiveAbility}). */
    @Column(columnDefinition = "double precision default 50")
    private double defensiveAbility = 50;

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
     * Unattended simulation mode. When enabled for the human manager, CONTINUE
     * never stops for optional interaction and matches use the instant engine.
     * The manager is also protected from dismissal so a long multi-season run
     * cannot strand the career on the job-selection screen.
     */
    @Column(columnDefinition = "boolean default false")
    private boolean alwaysContinue = false;

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

    /**
     * Boardroom press dynamics (Faza 6). An owner who overrules his coach grows more
     * arrogant; the overruled coach grows more humiliated. Both 0-100, fed by press
     * responses and the size of the owner's restrictions; high humiliation pushes the
     * coach toward leaving and drags squad morale down.
     */
    @Column(columnDefinition = "double precision default 0")
    private double ownerArrogance = 0;
    @Column(columnDefinition = "double precision default 0")
    private double coachHumiliation = 0;

}
