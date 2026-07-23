package com.footballmanagergamesimulator.dynamics;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One participant's persisted reaction to a {@link TeamMeeting}. The tier is
 * snapshotted so history reflects the hierarchy at meeting time.
 */
@Entity
@Data
@Table(name = "team_meeting_reaction",
        indexes = @Index(name = "idx_tm_reaction_meeting", columnList = "meetingId"))
public class TeamMeetingReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long meetingId;
    private long teamId;
    private long playerId;

    @Column(length = 120)
    private String playerName;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private HierarchyTier tier;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DynamicsReaction reaction;

    private double moraleBefore;
    private double moraleDelta;

    @Column(length = 500)
    private String reason;
}
