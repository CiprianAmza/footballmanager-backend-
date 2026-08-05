package com.footballmanagergamesimulator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/** One canonical, attacking-direction-normalized shot used by the in-game Opta-style Data Hub. */
@Entity
@Data
@Table(name = "shot_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_stats_id", "team_id", "shot_index"}),
        indexes = {
                @Index(name = "idx_shot_team_season", columnList = "team_id,season_number"),
                @Index(name = "idx_shot_comp_season", columnList = "competition_id,season_number")
        })
public class ShotEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "match_stats_id", nullable = false)
    private long matchStatsId;
    @Column(name = "shot_index", nullable = false)
    private int shotIndex;
    @Column(name = "competition_id", nullable = false)
    private long competitionId;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "round_number", nullable = false)
    private int roundNumber;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(name = "opponent_team_id", nullable = false)
    private long opponentTeamId;

    // MINUTE is a reserved keyword in H2. Leaving Hibernate's implicit column
    // name here prevents the entire SHOT_EVENT table from being created.
    @Column(name = "event_minute", nullable = false)
    private int minute;
    private double originX;
    private double originY;
    private double goalMouthY;
    private double goalMouthZ;
    private double distanceMeters;
    private double angleDegrees;
    /** Stored as ten-thousandths: 0.1234 xG = 1234. */
    private int xg;
    /** Post-shot xG; zero for off-target and blocked attempts. */
    private int xgot;

    private String outcome;
    private String situation;
    private String creationType;
    private String channel;
    private String bodyPart;
    private boolean insideBox;
    private boolean bigChance;
    private boolean underPressure;
    private boolean onTarget;
    private String sequenceLabel;
    /** MODELED for aggregate-derived events; OBSERVED when spatial live-event capture is available. */
    private String dataQuality;
}
