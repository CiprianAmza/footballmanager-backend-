package com.footballmanagergamesimulator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/** A club-specific, permanent induction chosen by the club's manager. */
@Entity
@Data
@Table(name = "club_legend", uniqueConstraints = @UniqueConstraint(
        name = "uk_club_legend_team_player", columnNames = {"team_id", "player_id"}))
public class ClubLegend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "team_id", nullable = false)
    private long teamId;

    @Column(name = "player_id", nullable = false)
    private long playerId;

    @Column(name = "player_name", nullable = false)
    private String playerName;

    @Column(name = "inducted_season", nullable = false)
    private int inductedSeason;

    @Column(name = "inducted_at", nullable = false)
    private long inductedAt;

    @Column(name = "reason", length = 240)
    private String reason;
}
