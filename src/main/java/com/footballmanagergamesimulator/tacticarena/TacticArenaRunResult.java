package com.footballmanagergamesimulator.tacticarena;

import jakarta.persistence.*;
import lombok.Data;

/** The collected season of a run, stored as gzip-compressed JSON. */
@Entity
@Data
@Table(name = "tactic_arena_run_result")
public class TacticArenaRunResult {

    @Id
    private long runId;

    private long testId;

    @Lob
    @Column(nullable = false)
    private byte[] gzJson;
}
