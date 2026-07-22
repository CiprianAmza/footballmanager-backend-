package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * A DERIVED projection: for each player who took part, his minute span and total
 * minutes played, computed from the canonical {@link MatchParticipant}s and
 * {@link MatchSubstitution}s. Persisted for cheap "minutes played" queries, but
 * NOT the source of truth — the participants and substitutions are.
 *
 * <p>{@code exitMinute} is null when the player finished the match, so he counts
 * as on the pitch at the final minute (a 90'/120' goal is his).
 */
@Entity
@Table(name = "match_appearance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_plan_id", "player_id"}))
public class MatchAppearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_plan_id")
    private MatchPlan matchPlan;

    private long teamId;

    @Column(name = "player_id")
    private long playerId;

    private int startMinute;

    /** Null when the player finished the match (never subbed off). */
    private Integer exitMinute;

    private int minutesPlayed;

    protected MatchAppearance() {} // JPA

    public MatchAppearance(MatchPlan matchPlan, long teamId, long playerId,
                           int startMinute, Integer exitMinute, int minutesPlayed) {
        this.matchPlan = matchPlan;
        this.teamId = teamId;
        this.playerId = playerId;
        this.startMinute = startMinute;
        this.exitMinute = exitMinute;
        this.minutesPlayed = minutesPlayed;
    }

    public long getId() { return id; }
    public MatchPlan getMatchPlan() { return matchPlan; }
    public long getTeamId() { return teamId; }
    public long getPlayerId() { return playerId; }
    public int getStartMinute() { return startMinute; }
    public Integer getExitMinute() { return exitMinute; }
    public int getMinutesPlayed() { return minutesPlayed; }

    /** True if on the pitch at {@code minute} — inclusive of the final minute for a finisher. */
    public boolean onPitchAt(int minute) {
        return minute >= startMinute && (exitMinute == null || minute < exitMinute);
    }
}
