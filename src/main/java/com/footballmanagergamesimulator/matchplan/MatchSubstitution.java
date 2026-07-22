package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * A canonical substitution: {@code onPlayerId} replaced {@code offPlayerId} at
 * {@code minute}. Ordered per team by {@code subIndex}, so the timeline reloads
 * deterministically. Source of truth for who was on the pitch when, together with
 * {@link MatchParticipant}.
 */
@Entity
@Table(name = "match_substitution",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_plan_id", "team_id", "sub_index"}))
public class MatchSubstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_plan_id")
    private MatchPlan matchPlan;

    @Column(name = "team_id")
    private long teamId;

    @Column(name = "sub_index")
    private int subIndex;

    @Column(name = "sub_minute") // "minute" is a reserved word in H2
    private int minute;

    private long offPlayerId;
    private long onPlayerId;

    protected MatchSubstitution() {} // JPA

    public MatchSubstitution(MatchPlan matchPlan, long teamId, int subIndex, int minute,
                             long offPlayerId, long onPlayerId) {
        this.matchPlan = matchPlan;
        this.teamId = teamId;
        this.subIndex = subIndex;
        this.minute = minute;
        this.offPlayerId = offPlayerId;
        this.onPlayerId = onPlayerId;
    }

    public long getId() { return id; }
    public MatchPlan getMatchPlan() { return matchPlan; }
    public long getTeamId() { return teamId; }
    public int getSubIndex() { return subIndex; }
    public int getMinute() { return minute; }
    public long getOffPlayerId() { return offPlayerId; }
    public long getOnPlayerId() { return onPlayerId; }
}
