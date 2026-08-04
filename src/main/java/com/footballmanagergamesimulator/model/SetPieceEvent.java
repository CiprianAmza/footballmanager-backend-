package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** One attacking set-piece occurrence with transparent modeled delivery details. */
@Entity @Data
@Table(name = "set_piece_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_stats_id", "team_id", "event_index"}),
        indexes = @Index(name = "idx_set_piece_team_season", columnList = "team_id,season_number"))
public class SetPieceEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private long id;
    @Column(name = "match_stats_id", nullable = false) private long matchStatsId;
    @Column(name = "event_index", nullable = false) private int eventIndex;
    @Column(name = "competition_id", nullable = false) private long competitionId;
    @Column(name = "season_number", nullable = false) private int seasonNumber;
    @Column(name = "round_number", nullable = false) private int roundNumber;
    @Column(name = "team_id", nullable = false) private long teamId;
    @Column(name = "opponent_team_id", nullable = false) private long opponentTeamId;
    private String type;
    private String deliveryStyle;
    private String deliveryZone;
    private String firstContact;
    private String secondBallRecovery;
    private String outcome;
    /** xG in ten-thousandths, reconciled with the associated modeled ShotEvent. */
    private int xg;
    private String dataQuality;
}
