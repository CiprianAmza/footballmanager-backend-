package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * One scheduled goal in a {@link MatchPlan}. The scoring team, minute, phase and
 * type are fixed by the planner; the scorer and assister are left null until the
 * goal is executed (live: at that minute from the players on the pitch; instant:
 * from the simulated on-pitch set). Once resolved and persisted, the pair never
 * changes — a refresh reloads the same slot and re-execution is a no-op.
 *
 * <p>Unique within a plan by {@code slotIndex}; that index also seeds the slot's
 * resolution RNG, so it must be stable across reloads.
 */
@Entity
@Table(name = "match_plan_goal_slot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_plan_id", "slot_index"}))
public class GoalSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "slot_index")
    private int slotIndex;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_plan_id")
    private MatchPlan matchPlan;

    private long teamId;

    @Column(name = "goal_minute") // "minute" is a reserved word in H2
    private int minute;

    @Enumerated(EnumType.STRING)
    private GoalPhase phase;

    private String goalType; // OPEN_PLAY | PENALTY | FREE_KICK | HEADER

    private Long scorerId;
    private Long assistId;
    private boolean resolved;

    protected GoalSlot() {} // JPA

    public GoalSlot(long teamId, int minute, GoalPhase phase, String goalType) {
        this.teamId = teamId;
        this.minute = minute;
        this.phase = phase;
        this.goalType = goalType;
    }

    public long getId() { return id; }
    public int getSlotIndex() { return slotIndex; }
    public void setSlotIndex(int slotIndex) { this.slotIndex = slotIndex; }
    public MatchPlan getMatchPlan() { return matchPlan; }
    public void setMatchPlan(MatchPlan matchPlan) { this.matchPlan = matchPlan; }
    public long getTeamId() { return teamId; }
    public int getMinute() { return minute; }
    public GoalPhase getPhase() { return phase; }
    public String getGoalType() { return goalType; }
    public Long getScorerId() { return scorerId; }
    public Long getAssistId() { return assistId; }
    public boolean isResolved() { return resolved; }

    /** Record the resolved contributors and mark the slot done. Idempotent-safe:
     *  callers must check {@link #isResolved()} before re-resolving. */
    public void resolve(Long scorerId, Long assistId) {
        this.scorerId = scorerId;
        this.assistId = assistId;
        this.resolved = true;
    }
}
