package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One team's league chances after a number of games (design DESIGN_MEDIA_SI_COMPETITII §4.3): the points of the
 * "title race" chart. Chances are fractions 0..1.
 */
@Entity
@Data
@Table(name = "title_odds_snapshot",
        indexes = @Index(name = "ix_title_odds_comp_season", columnList = "competitionId, seasonNumber"))
public class TitleOddsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long competitionId;
    private int seasonNumber;
    /** Games played by the team with the most games when the chances were computed (0 = before the season). */
    private int gamesPlayed;
    private long teamId;
    private double title;
    private double top3;
    private double champions;
    private double relegation;
    private double averagePosition;
}
