package com.footballmanagergamesimulator.tacticarena;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** A captured panel of a run (tactic / schedule / competitions), kept in its own WebP/PNG format. */
@Entity
@Data
@Table(name = "tactic_arena_image", uniqueConstraints = @UniqueConstraint(columnNames = {"runId", "kind"}))
public class TacticArenaImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long testId;
    private long runId;
    private String kind;
    private String contentType;
    private Instant createdAt;

    @Lob
    @Column(nullable = false)
    private byte[] data;
}
