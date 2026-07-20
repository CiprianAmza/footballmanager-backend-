package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "injury", indexes = {
        @Index(name = "idx_injury_team_active", columnList = "teamId,daysRemaining"),
        @Index(name = "idx_injury_player_active", columnList = "playerId,daysRemaining"),
        @Index(name = "idx_injury_return_date", columnList = "return_season,return_day,daysRemaining")
})
public class Injury {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private long teamId;

    private String injuryType;
    private String severity;
    /**
     * Compatibility flag/duration for old saves. Positive means the injury is active;
     * zero means recovered. It is no longer decremented every calendar day.
     */
    private int daysRemaining;
    private int seasonNumber;

    /** Absolute in-game date on which the player becomes available again. */
    @Column(name = "return_day")
    private int returnDay;
    @Column(name = "return_season")
    private int returnSeason;

}
