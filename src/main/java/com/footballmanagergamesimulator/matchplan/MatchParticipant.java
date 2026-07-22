package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * A canonical squad member for a match: a starter or a bench player, with the
 * position and slot index he was fielded in, plus a snapshot of the attributes
 * the {@link ContributionResolver} used. Persisting the snapshot means a reload /
 * re-execution reproduces the same scorer even if the player's live data has
 * changed since. Together with {@link MatchSubstitution} this is the source of
 * truth for the appearance timeline; {@link MatchAppearance} is derived from it.
 */
@Entity
@Table(name = "match_participant",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"match_plan_id", "player_id"}),
                @UniqueConstraint(columnNames = {"match_plan_id", "team_id", "participant_index"})
        })
public class MatchParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_plan_id")
    private MatchPlan matchPlan;

    @Column(name = "team_id")
    private long teamId;

    @Column(name = "player_id")
    private long playerId;

    /** Stable ordinal within the team's list (starters then bench). */
    @Column(name = "participant_index")
    private int participantIndex;

    private String name;
    private String position;

    /** True for the starting eleven, false for the bench. */
    private boolean starter;

    // Resolver snapshot — the exact inputs used to pick scorers/assisters.
    private double rating;
    private double fitness;
    private int finishing;
    private int passing;
    private int vision;
    private boolean penaltyTaker;
    private boolean freeKickTaker;

    protected MatchParticipant() {} // JPA

    public static MatchParticipant of(MatchPlan plan, long teamId, int participantIndex,
                                      boolean starter, Contributor c) {
        MatchParticipant p = new MatchParticipant();
        p.matchPlan = plan;
        p.teamId = teamId;
        p.participantIndex = participantIndex;
        p.starter = starter;
        p.playerId = c.playerId();
        p.name = c.name();
        p.position = c.position();
        p.rating = c.rating();
        p.fitness = c.fitness();
        p.finishing = c.finishing();
        p.passing = c.passing();
        p.vision = c.vision();
        p.penaltyTaker = c.designatedPenaltyTaker();
        p.freeKickTaker = c.designatedFreeKickTaker();
        return p;
    }

    /** Rebuild the resolver's view of this player from the persisted snapshot. */
    public Contributor toContributor() {
        return new Contributor(playerId, name, position, rating, finishing, passing, vision,
                fitness, penaltyTaker, freeKickTaker);
    }

    public long getId() { return id; }
    public MatchPlan getMatchPlan() { return matchPlan; }
    public long getTeamId() { return teamId; }
    public long getPlayerId() { return playerId; }
    public int getParticipantIndex() { return participantIndex; }
    public String getName() { return name; }
    public String getPosition() { return position; }
    public boolean isStarter() { return starter; }
    public double getRating() { return rating; }
    public double getFitness() { return fitness; }
    public int getFinishing() { return finishing; }
    public int getPassing() { return passing; }
    public int getVision() { return vision; }
    public boolean isPenaltyTaker() { return penaltyTaker; }
    public boolean isFreeKickTaker() { return freeKickTaker; }
}
