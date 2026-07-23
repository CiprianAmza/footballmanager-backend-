package com.footballmanagergamesimulator.dynamics;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A promise the manager made in a team meeting or player conversation. Its
 * lifecycle (OPEN → KEPT/BROKEN/CANCELLED) feeds morale and future reactions.
 */
@Entity
@Data
@Table(name = "dynamics_promise",
        indexes = {
                @Index(name = "idx_dyn_promise_team", columnList = "teamId"),
                @Index(name = "idx_dyn_promise_player", columnList = "playerId")
        })
public class DynamicsPromise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long playerId;

    @Column(length = 120)
    private String playerName;

    private int season;
    private int monthIndex;
    private int createdDay;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PromiseSource source;

    private long sourceId;               // id of the originating meeting or conversation

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PromiseType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PromiseStatus status;

    @Column(length = 500)
    private String description;

    private int dueSeason;
    private int dueDay;

    private long createdAtEpochMillis;
    private Long resolvedAtEpochMillis;  // nullable until resolved

    @Version
    private long version;
}
