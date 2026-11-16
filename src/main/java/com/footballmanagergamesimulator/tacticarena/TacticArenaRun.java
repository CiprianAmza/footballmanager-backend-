package com.footballmanagergamesimulator.tacticarena;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One season simulation of a test (index 0 = chosen club, 1..3 = reference clubs). */
@Entity
@Data
@Table(name = "tactic_arena_run", indexes = @Index(name = "idx_tactic_arena_run_test", columnList = "testId"))
public class TacticArenaRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long testId;
    private int runIndex;
    private String kind;

    private long teamId;
    private String teamName;
    private int teamReputation;

    private String status;
    private int currentDay;
    private int simulatedDays;
    private int totalDays;
    @Column(length = 4000)
    private String message;

    private Instant startedAt;
    private Instant finishedAt;

    /** League totals of the run ({@link TacticArenaStats.RunTotals} as JSON), once DONE. */
    @Column(length = 4000)
    private String totalsJson;

    /** Final place in the club's own league and the league's size, once DONE. */
    private Integer finalPosition;
    private Integer finalLeagueSize;

    /** Diamond League totals of the run ({totals, position, of} as JSON), when its club plays in it. */
    @Column(length = 4000)
    private String diamondJson;
}
