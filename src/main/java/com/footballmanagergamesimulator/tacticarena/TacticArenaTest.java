package com.footballmanagergamesimulator.tacticarena;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One tactic test: four season simulations of the same tactic. */
@Entity
@Data
@Table(name = "tactic_arena_test")
public class TacticArenaTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Stable identity across database wipes (export/import key). */
    @Column(nullable = false, unique = true, length = 40)
    private String uid;

    @Column(length = 100)
    private String name;
    private String owner;
    private String formation;

    private long teamId;
    private String teamName;
    private long leagueId;
    private String leagueName;

    private int season;
    private int startDay;
    private int endDay;
    private int leagueMatchesTotal;
    private int leagueMatchesRemaining;
    private String engine;

    private String status;
    @Column(length = 4000)
    private String message;

    private Instant createdAt;
    private Instant finishedAt;

    /** The submitted {@code PersonalizedTacticView}, as JSON. */
    @Lob
    private String tacticJson;

    /** Folder name under {@code tactic-arena.export-dir}, once exported. */
    private String exportFolder;
}
