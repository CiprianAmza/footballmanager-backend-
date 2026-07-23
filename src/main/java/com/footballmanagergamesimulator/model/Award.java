package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "award")
public class Award {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int seasonNumber;
    private String awardType; // BEST_PLAYER, TOP_SCORER, GOLDEN_BOOT, BALLON_DOR, etc.
    private long competitionId;
    private String competitionName;
    private long winnerId;
    private String winnerName;
    private long winnerTeamId;
    private String winnerTeamName;
    @Column(name = "award_value")
    private String value; // nullable - e.g. "23 goals"

    // Structured evidence for historical award pages and player profile badges.
    @Column(columnDefinition = "double precision default 0")
    private double votingPoints;
    @Column(columnDefinition = "int default 0")
    private int firstPlaceVotes;
    @Column(columnDefinition = "double precision default 0")
    private double averageRating;
    @Column(columnDefinition = "int default 0")
    private int goals;
    @Column(columnDefinition = "int default 0")
    private int assists;
    @Column(columnDefinition = "int default 0")
    private int appearances;
    @Column(columnDefinition = "double precision default 0")
    private double chancesCreated;
    @Column(columnDefinition = "double precision default 0")
    private double dribblesCompleted;
    @Column(columnDefinition = "int default 0")
    private int saves;
    @Column(columnDefinition = "int default 0")
    private int cleanSheets;
    @Column(columnDefinition = "int default 0")
    private int goalsConceded;
    /** True when the admin selected the winner while the statistics remain visible. */
    @Column(columnDefinition = "boolean default false")
    private boolean adminSelected;
    /** Non-null for the eleven TEAM_OF_YEAR rows (GK, LB, CB1 ... ST). */
    private String selectionSlot;
}
