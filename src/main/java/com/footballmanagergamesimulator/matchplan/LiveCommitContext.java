package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * A self-contained, persisted snapshot of the context a LIVE canonical match needs to
 * COMMIT correctly after a cold restart — the pieces the controller normally sets on the
 * in-memory session via {@code setDeferredContext}, which would otherwise be lost.
 *
 * <p>Keyed by the live session key ({@code competitionId_season_round_teamId1_teamId2}) so
 * recovery can resolve it directly — including a two-leg European fixture whose leg number
 * a plain fixture lookup could not disambiguate. The canonical commit SCORE comes from the
 * plan (not a rescore), so the two-axis profiles/vectors are deliberately NOT stored; only
 * the scalar context the coordinator uses (tactics, knockout leg/tie/bracket) is.
 */
@Entity
@Table(name = "live_commit_context",
        uniqueConstraints = @UniqueConstraint(columnNames = {"live_key"}))
public class LiveCommitContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "live_key")
    private String liveKey;

    /** The CTIM fixture row id, so recovery can derive the canonical {@code CTIM:} fixture key. */
    private long matchRowId;

    private String homeTactic;
    private String awayTactic;
    private double homePower;
    private double awayPower;

    private boolean knockout;
    private int legNumber;
    private long tieId;
    private int matchIndex;

    // --- Live checkpoint (updated on every /advance) so recovery RESUMES at the crash
    // minute instead of replaying from 0 (which would re-roll red cards / AI subs and
    // change the on-pitch candidates for unresolved future goal slots). ---
    @Column(name = "checkpoint_minute")
    private int checkpointMinute;
    /** CSV of playerIds sent off (red card) by the checkpoint minute — non-cosmetic, they
     *  are off the pitch for future slot resolution. */
    @Column(name = "red_card_player_ids", length = 500)
    private String redCardPlayerIds = "";

    /** Exact state of the live narration RNG after {@link #checkpointMinute}.  Without it,
     *  a restart can roll a different future red card / AI substitution and therefore alter
     *  the eligible scorer set for a later canonical goal. */
    @Column(name = "checkpoint_random_state")
    private Long checkpointRandomState;

    /** Full resumable live state (statistics, player stamina/cards, timeline and deferred
     *  non-goal events). Canonical goal animations are stored separately as versioned recipes. */
    @Lob
    @Column(name = "checkpoint_json")
    private String checkpointJson;

    protected LiveCommitContext() {} // JPA

    public LiveCommitContext(String liveKey, long matchRowId, String homeTactic, String awayTactic,
                             double homePower, double awayPower, boolean knockout,
                             int legNumber, long tieId, int matchIndex) {
        this.liveKey = liveKey;
        this.matchRowId = matchRowId;
        this.homeTactic = homeTactic;
        this.awayTactic = awayTactic;
        this.homePower = homePower;
        this.awayPower = awayPower;
        this.knockout = knockout;
        this.legNumber = legNumber;
        this.tieId = tieId;
        this.matchIndex = matchIndex;
    }

    public long getId() { return id; }
    public String getLiveKey() { return liveKey; }
    public long getMatchRowId() { return matchRowId; }
    public String getHomeTactic() { return homeTactic; }
    public String getAwayTactic() { return awayTactic; }
    public double getHomePower() { return homePower; }
    public double getAwayPower() { return awayPower; }
    public boolean isKnockout() { return knockout; }
    public int getLegNumber() { return legNumber; }
    public long getTieId() { return tieId; }
    public int getMatchIndex() { return matchIndex; }

    public int getCheckpointMinute() { return checkpointMinute; }
    public void setCheckpointMinute(int checkpointMinute) { this.checkpointMinute = checkpointMinute; }
    public String getRedCardPlayerIds() { return redCardPlayerIds; }
    public void setRedCardPlayerIds(String redCardPlayerIds) { this.redCardPlayerIds = redCardPlayerIds; }
    public Long getCheckpointRandomState() { return checkpointRandomState; }
    public void setCheckpointRandomState(Long checkpointRandomState) { this.checkpointRandomState = checkpointRandomState; }
    public String getCheckpointJson() { return checkpointJson; }
    public void setCheckpointJson(String checkpointJson) { this.checkpointJson = checkpointJson; }
}
