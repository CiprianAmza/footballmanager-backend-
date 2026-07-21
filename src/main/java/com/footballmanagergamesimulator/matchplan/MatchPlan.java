package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The single canonical description of a match: its final scoreline (regular time,
 * optional extra time, optional shootout) plus the scheduled goal slots. Both the
 * live executor and the instant executor run this exact plan, so a watched match
 * and an un-watched one produce identical scores and — with the same seed and no
 * manual interventions — identical scorers.
 *
 * <p>Identified by the real {@code fixtureKey} (e.g. {@code "CTIM:<matchRowId>"}),
 * unique so a plan is reused across reloads/refreshes rather than regenerated.
 * Shootout penalties are stored separately and never count as goals.
 */
@Entity
@Table(name = "match_plan",
        uniqueConstraints = @UniqueConstraint(columnNames = "fixture_key"))
public class MatchPlan {

    public enum Status { PLANNED, IN_PROGRESS, COMPLETED, COMMITTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Stable identity of the real fixture (namespaced per match type). */
    @Column(name = "fixture_key", nullable = false, unique = true)
    private String fixtureKey;

    private long seed;
    private String algorithmVersion;

    private long homeTeamId;
    private long awayTeamId;

    private int homeScore90;
    private int awayScore90;

    /** Extra-time goals for each side; -1 when no extra time was played. */
    private int homeScoreET;
    private int awayScoreET;

    /** Shootout result; -1 when no shootout was played. Not part of scorer stats. */
    private int homeShootout;
    private int awayShootout;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PLANNED;

    @OneToMany(mappedBy = "matchPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("slotIndex ASC")
    private List<GoalSlot> goalSlots = new ArrayList<>();

    protected MatchPlan() {} // JPA

    public MatchPlan(String fixtureKey, long seed, String algorithmVersion,
                     long homeTeamId, long awayTeamId,
                     int homeScore90, int awayScore90,
                     int homeScoreET, int awayScoreET,
                     int homeShootout, int awayShootout,
                     List<GoalSlot> goalSlots) {
        this.fixtureKey = fixtureKey;
        this.seed = seed;
        this.algorithmVersion = algorithmVersion;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.homeScore90 = homeScore90;
        this.awayScore90 = awayScore90;
        this.homeScoreET = homeScoreET;
        this.awayScoreET = awayScoreET;
        this.homeShootout = homeShootout;
        this.awayShootout = awayShootout;
        this.goalSlots = goalSlots != null ? goalSlots : new ArrayList<>();
        for (GoalSlot slot : this.goalSlots) slot.setMatchPlan(this);
    }

    public long getId() { return id; }
    public String getFixtureKey() { return fixtureKey; }
    public long getSeed() { return seed; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public long getHomeTeamId() { return homeTeamId; }
    public long getAwayTeamId() { return awayTeamId; }
    public int getHomeScore90() { return homeScore90; }
    public int getAwayScore90() { return awayScore90; }
    public int getHomeScoreET() { return homeScoreET; }
    public int getAwayScoreET() { return awayScoreET; }
    public int getHomeShootout() { return homeShootout; }
    public int getAwayShootout() { return awayShootout; }
    public List<GoalSlot> getGoalSlots() { return goalSlots; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public boolean hadExtraTime() { return homeScoreET >= 0 && awayScoreET >= 0; }
    public boolean hadShootout() { return homeShootout >= 0 && awayShootout >= 0; }

    /** Total football goals per side (regular + extra time), excluding the shootout. */
    public int getHomeGoals() { return homeScore90 + Math.max(0, homeScoreET); }
    public int getAwayGoals() { return awayScore90 + Math.max(0, awayScoreET); }
}
