package com.footballmanagergamesimulator.dynamics;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A contextual monthly conversation with a single player. At most one exists per
 * player per calendar month of a season, enforced by the unique key
 * (player_id, season, month_index) — this blocks spam via refresh/retry and
 * survives restart and save/load.
 */
@Entity
@Data
@Table(name = "player_conversation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_player_conversation_player_season_month",
                columnNames = {"playerId", "season", "monthIndex"}))
public class PlayerConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long playerId;

    @Column(length = 120)
    private String playerName;

    private int season;
    private int monthIndex;              // 1-12 calendar month within the season

    @Column(name = "held_day")
    private int day;                     // GameCalendar.currentDay when held

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ConversationTopic topic;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private DynamicsTone tone;

    private double managerReputation;    // snapshot at conversation time

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DynamicsReaction reaction;

    private double moraleBefore;
    private double moraleDelta;

    @Column(length = 1000)
    private String playerResponse;

    @Column(length = 1000)
    private String summary;

    private long createdAtEpochMillis;

    @Version
    private long version;
}
