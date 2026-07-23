package com.footballmanagergamesimulator.training;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Membership of a player in a {@link MentoringGroup}. The {@code mentor} flag
 * is derived and cached at group-save time (senior, high leadership /
 * determination), but can be forced by the user.
 */
@Entity
@Data
@Table(name = "mentoringGroupMember")
public class MentoringGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long groupId;
    private long teamId;
    private long playerId;

    /** True if this member acts as a mentor (vs. a mentee). */
    private boolean mentor;

    @Version
    private long version;
}
